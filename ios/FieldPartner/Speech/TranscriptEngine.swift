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
  /// Bumped for every new task. Callbacks from a cancelled or replaced task carry an old
  /// value and are ignored, so they can't end the current utterance early or kill its task.
  private var generation = 0
  /// Format of the buffers the current request has received. A request can't take a new
  /// format mid-stream (glasses connect, call starts), so a change starts a new task.
  private var requestFormat: AVAudioFormat?
  private var running = false
  private var lastPartial = ""
  private var taskStartedAt = Date()
  private var taskHadResult = false
  private var silenceTimer: DispatchSourceTimer?
  private var lastSpeechAt = Date()
  private var consecutiveFailures = 0
  private var retryPending = false
  /// Words the recognizer should expect: the wake phrase and the assistant's name.
  private var hints: [String] = ["hey dispatch", "dispatch", "partner"]

  static func requestPermission() async -> Bool {
    await withCheckedContinuation { cont in
      SFSpeechRecognizer.requestAuthorization { cont.resume(returning: $0 == .authorized) }
    }
  }

  /// Delay before the next task after `failures` quick failures in a row: none at first, then
  /// 0.5 s doubling to 8 s. Stops a tight loop when recognition is unavailable (not authorized,
  /// no on-device model, recognizer busy) without slowing normal rollovers.
  static func retryDelay(failures: Int) -> TimeInterval {
    guard failures > 0 else { return 0 }
    return min(8, 0.5 * pow(2, Double(failures - 1)))
  }

  /// Sets recognition hints, for example a company's own wake phrase. Safe to call any time.
  func setHints(_ phrases: [String]) {
    let cleaned = phrases.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
    queue.async { self.hints = Array(Set(cleaned + ["partner"])) }
  }

  func start(locale: String) {
    queue.async {
      guard !self.running else { return }
      let r = SFSpeechRecognizer(locale: Locale(identifier: locale)) ?? SFSpeechRecognizer()
      self.recognizer = r
      self.running = true
      self.consecutiveFailures = 0
      self.beginTask()
      self.startSilenceWatch()
    }
  }

  func stop() {
    queue.async {
      self.running = false
      self.silenceTimer?.cancel()
      self.silenceTimer = nil
      // finish() lets the last result arrive, so a sentence cut off by the visit ending is kept.
      self.request?.endAudio()
      self.task?.finish()
      self.task = nil
      self.request = nil
      self.requestFormat = nil
    }
  }

  /// Audio from whichever source is live (LiveKit microphone or the local fallback).
  func append(_ buffer: AVAudioPCMBuffer) {
    queue.async {
      guard self.running, buffer.frameLength > 0 else { return }
      if let format = self.requestFormat, format != buffer.format, self.request != nil {
        self.finishUtterance()
        self.endCurrentTask(cancel: true)
        self.beginTask()
      }
      guard let request = self.request else { return }
      if self.requestFormat == nil { self.requestFormat = buffer.format }
      request.append(buffer)
    }
  }

  private func beginTask() {
    guard running, task == nil, let recognizer else { return }
    guard recognizer.isAvailable else {
      consecutiveFailures += 1
      scheduleRetry()
      return
    }
    generation += 1
    let gen = generation
    let req = SFSpeechAudioBufferRecognitionRequest()
    req.shouldReportPartialResults = true
    if recognizer.supportsOnDeviceRecognition { req.requiresOnDeviceRecognition = true }
    req.addsPunctuation = true
    req.taskHint = .dictation
    req.contextualStrings = hints
    request = req
    requestFormat = nil
    lastPartial = ""
    taskStartedAt = Date()
    taskHadResult = false
    task = recognizer.recognitionTask(with: req) { [weak self] result, error in
      guard let self else { return }
      self.queue.async {
        guard gen == self.generation else { return }  // a replaced task's late callback
        if let result {
          self.taskHadResult = true
          self.consecutiveFailures = 0
          let text = result.bestTranscription.formattedString
          if !text.isEmpty, text != self.lastPartial {
            self.lastPartial = text
            self.lastSpeechAt = Date()
            self.onPartial?(text)
          }
          if result.isFinal {
            self.finishUtterance()
            self.endCurrentTask(cancel: false)
            self.rollOver()
            return
          }
        }
        if error != nil {
          // An error right after starting, with nothing heard, is a failure to back off from.
          // An error after speech (silence timeout, interruption) is a normal end of a task.
          if !self.taskHadResult && Date().timeIntervalSince(self.taskStartedAt) < 2 {
            self.consecutiveFailures += 1
          }
          self.finishUtterance()
          self.endCurrentTask(cancel: false)
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

  /// Drops the current task. Its late callbacks are ignored because the generation moves on.
  private func endCurrentTask(cancel: Bool) {
    generation += 1
    request?.endAudio()
    if cancel { task?.cancel() }
    task = nil
    request = nil
    requestFormat = nil
  }

  /// Starts the next task at once, so no audio is lost between tasks, unless recognition keeps
  /// failing, in which case it waits a little longer each time.
  private func rollOver() {
    guard running else { return }
    if consecutiveFailures > 0 {
      scheduleRetry()
    } else {
      beginTask()
    }
  }

  private func scheduleRetry() {
    guard running, !retryPending else { return }
    retryPending = true
    let delay = Self.retryDelay(failures: consecutiveFailures)
    queue.asyncAfter(deadline: .now() + delay) {
      self.retryPending = false
      self.beginTask()
    }
  }

  /// A pause of 1.2 s ends the utterance, so "hey dispatch, what's this code" is acted on
  /// right after the technician stops talking instead of when Apple decides.
  /// Tasks also roll over every 50 s to stay under the recognizer's limit.
  private func startSilenceWatch() {
    let t = DispatchSource.makeTimerSource(queue: queue)
    t.schedule(deadline: .now() + 0.3, repeating: 0.3)
    t.setEventHandler { [weak self] in
      guard let self, self.running, self.task != nil else { return }
      let quiet = Date().timeIntervalSince(self.lastSpeechAt)
      let age = Date().timeIntervalSince(self.taskStartedAt)
      if (!self.lastPartial.isEmpty && quiet > 1.2) || age > 50 {
        self.finishUtterance()
        self.endCurrentTask(cancel: true)
        self.rollOver()
      }
    }
    t.resume()
    silenceTimer = t
  }
}
