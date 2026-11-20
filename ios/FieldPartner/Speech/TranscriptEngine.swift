import AVFoundation
import Foundation
import Speech

/// Continuous on-device transcription of the whole visit. Feeds the visit log and
/// the wake-phrase detector. Nothing here leaves the phone.
///
/// Apple's recognizer ends a task after about a minute or on long silences, so the
/// engine rolls over to a new task whenever one finishes, without dropping audio.
final class TranscriptEngine: @unchecked Sendable {
  struct Segment {
    let text: String
    let isFinal: Bool
    let at: Date
  }

  var onPartial: ((String) -> Void)?
  var onFinal: ((String) -> Void)?

  private let queue = DispatchQueue(label: "fieldpartner.transcript")
  private var recognizer: SFSpeechRecognizer?
  private var request: SFSpeechAudioBufferRecognitionRequest?
  private var task: SFSpeechRecognitionTask?
  private var running = false
  private var lastPartial = ""
  private var taskStartedAt = Date()
  private var silenceTimer: DispatchSourceTimer?
  private var lastSpeechAt = Date()

  static func requestPermission() async -> Bool {
    await withCheckedContinuation { cont in
      SFSpeechRecognizer.requestAuthorization { cont.resume(returning: $0 == .authorized) }
    }
  }

  func start(locale: String) {
    queue.async {
      guard !self.running else { return }
      let r = SFSpeechRecognizer(locale: Locale(identifier: locale)) ?? SFSpeechRecognizer()
      self.recognizer = r
      self.running = true
      self.beginTask()
      self.startSilenceWatch()
    }
  }

  func stop() {
    queue.async {
      self.running = false
      self.silenceTimer?.cancel()
      self.silenceTimer = nil
      self.request?.endAudio()
      self.task?.finish()
      self.task = nil
      self.request = nil
    }
  }

  /// Audio from whichever source is live (LiveKit microphone or the local fallback).
  func append(_ buffer: AVAudioPCMBuffer) {
    queue.async {
      guard self.running else { return }
      self.request?.append(buffer)
    }
  }

  private func beginTask() {
    guard running, let recognizer else { return }
    let req = SFSpeechAudioBufferRecognitionRequest()
    req.shouldReportPartialResults = true
    if recognizer.supportsOnDeviceRecognition { req.requiresOnDeviceRecognition = true }
    req.addsPunctuation = true
    req.taskHint = .dictation
    request = req
    lastPartial = ""
    taskStartedAt = Date()
    task = recognizer.recognitionTask(with: req) { [weak self] result, error in
      guard let self else { return }
      self.queue.async {
        if let result {
          let text = result.bestTranscription.formattedString
          if !text.isEmpty, text != self.lastPartial {
            self.lastPartial = text
            self.lastSpeechAt = Date()
            self.onPartial?(text)
          }
          if result.isFinal {
            self.finishUtterance()
            self.rollOver()
            return
          }
        }
        if error != nil {
          self.finishUtterance()
          self.rollOver()
        }
      }
    }
  }

  /// Emits the current utterance as final and starts a fresh one.
  private func finishUtterance() {
    let text = lastPartial.trimmingCharacters(in: .whitespacesAndNewlines)
    lastPartial = ""
    if !text.isEmpty { onFinal?(text) }
  }

  private func rollOver() {
    task = nil
    request = nil
    guard running else { return }
    queue.asyncAfter(deadline: .now() + 0.05) { self.beginTask() }
  }

  /// A pause of 1.2 s ends the utterance, so "hey dispatch, what's this code" is acted on
  /// right after the technician stops talking instead of when Apple decides.
  /// Tasks also roll over every 50 s to stay under the recognizer's limit.
  private func startSilenceWatch() {
    let t = DispatchSource.makeTimerSource(queue: queue)
    t.schedule(deadline: .now() + 0.3, repeating: 0.3)
    t.setEventHandler { [weak self] in
      guard let self, self.running else { return }
      let quiet = Date().timeIntervalSince(self.lastSpeechAt)
      let age = Date().timeIntervalSince(self.taskStartedAt)
      if (!self.lastPartial.isEmpty && quiet > 1.2) || age > 50 {
        self.finishUtterance()
        self.request?.endAudio()
        self.task?.cancel()
        self.rollOver()
      }
    }
    t.resume()
    silenceTimer = t
  }
}
