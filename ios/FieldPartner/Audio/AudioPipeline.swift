import AVFoundation
import Foundation
import LiveKit

/// Receives microphone buffers from LiveKit's audio engine while the call is up.
/// LiveKit owns the microphone during a call; tapping its buffers avoids a second
/// audio engine fighting over the glasses' Bluetooth mic.
final class LiveMicTap: NSObject, AudioRenderer, @unchecked Sendable {
  var onBuffer: ((AVAudioPCMBuffer) -> Void)?
  func render(pcmBuffer: AVAudioPCMBuffer) { onBuffer?(pcmBuffer) }
}

/// Microphone capture when there is no call (no signal yet, or live video not
/// configured). Prefers the glasses' Bluetooth mic.
final class LocalMicCapture: @unchecked Sendable {
  private let engine = AVAudioEngine()
  private(set) var running = false
  var onBuffer: ((AVAudioPCMBuffer) -> Void)?

  func start() throws {
    guard !running else { return }
    let session = AVAudioSession.sharedInstance()
    try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .allowBluetoothA2DP])
    try session.setActive(true)
    if let hfp = session.availableInputs?.first(where: { $0.portType == .bluetoothHFP }) {
      try? session.setPreferredInput(hfp)
    }
    let input = engine.inputNode
    let format = input.outputFormat(forBus: 0)
    input.removeTap(onBus: 0)
    input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
      self?.onBuffer?(buffer)
    }
    engine.prepare()
    try engine.start()
    running = true
  }

  func stop() {
    guard running else { return }
    engine.inputNode.removeTap(onBus: 0)
    engine.stop()
    running = false
  }
}

/// Writes the technician's microphone to AAC files in segments, alongside the video
/// segments. Format changes (call starting, Bluetooth switching) open a new file.
final class AudioFileRecorder: @unchecked Sendable {
  private let queue = DispatchQueue(label: "fieldpartner.audiofile")
  private let directory: URL
  private let prefix: String
  private let segmentSeconds: TimeInterval
  private var file: AVAudioFile?
  private var fileURL: URL?
  private var fileFormat: AVAudioFormat?
  private var openedAt = Date()
  private var index = 0
  private var enabled = false
  var onSegmentFinished: ((URL) -> Void)?

  init(directory: URL, prefix: String, segmentSeconds: TimeInterval = 300) {
    self.directory = directory
    self.prefix = prefix
    self.segmentSeconds = segmentSeconds
    try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
  }

  func start() { queue.async { self.enabled = true } }

  func stop() {
    queue.async {
      self.enabled = false
      self.close()
    }
  }

  func append(_ buffer: AVAudioPCMBuffer) {
    queue.async {
      guard self.enabled else { return }
      if self.file == nil || self.fileFormat != buffer.format || Date().timeIntervalSince(self.openedAt) > self.segmentSeconds {
        self.close()
        self.open(format: buffer.format)
      }
      do {
        try self.file?.write(from: buffer)
      } catch {
        NSLog("[AudioFile] write failed: %@", error.localizedDescription)
        self.close()
      }
    }
  }

  private func open(format: AVAudioFormat) {
    index += 1
    let url = directory.appendingPathComponent(String(format: "%@-audio-%03d.m4a", prefix, index))
    try? FileManager.default.removeItem(at: url)
    let settings: [String: Any] = [
      AVFormatIDKey: kAudioFormatMPEG4AAC,
      AVSampleRateKey: format.sampleRate,
      AVNumberOfChannelsKey: format.channelCount,
      AVEncoderBitRateKey: 64_000,
    ]
    do {
      file = try AVAudioFile(forWriting: url, settings: settings, commonFormat: format.commonFormat, interleaved: format.isInterleaved)
      fileURL = url
      fileFormat = format
      openedAt = Date()
    } catch {
      NSLog("[AudioFile] open failed: %@", error.localizedDescription)
      file = nil
    }
  }

  private func close() {
    guard file != nil, let url = fileURL else { return }
    file = nil  // AVAudioFile finalizes on deinit
    fileURL = nil
    fileFormat = nil
    onSegmentFinished?(url)
  }
}

/// Short tones played into the glasses: wake, close, alert, hint, recording.
enum Earcon {
  case wake, close, hint, alert, recordingOn, recordingOff, error

  private var tones: [(freq: Double, ms: Double)] {
    switch self {
    case .wake: return [(660, 70), (880, 90)]
    case .close: return [(880, 70), (660, 90)]
    case .hint: return [(1046, 60)]
    case .alert: return [(988, 160), (0, 60), (988, 160), (0, 60), (988, 220)]
    case .recordingOn: return [(523, 90), (784, 140)]
    case .recordingOff: return [(784, 90), (523, 140)]
    case .error: return [(330, 220)]
    }
  }

  private static var players: [AVAudioPlayer] = []

  @MainActor
  func play(volume: Float = 0.6) {
    guard let data = wav() else { return }
    do {
      let p = try AVAudioPlayer(data: data)
      p.volume = volume
      p.play()
      Earcon.players.append(p)
      Earcon.players.removeAll { !$0.isPlaying && $0 !== p }
    } catch {}
  }

  private func wav(sampleRate: Double = 16_000) -> Data? {
    var samples: [Int16] = []
    for t in tones {
      let n = Int(sampleRate * t.ms / 1000)
      for i in 0..<n {
        let env = min(1.0, Double(i) / 160.0, Double(n - i) / 160.0)
        let v = t.freq == 0 ? 0 : sin(2 * .pi * t.freq * Double(i) / sampleRate) * env * 0.5
        samples.append(Int16(v * Double(Int16.max)))
      }
    }
    var data = Data()
    let byteCount = samples.count * 2
    func u32(_ v: UInt32) { var x = v.littleEndian; data.append(Data(bytes: &x, count: 4)) }
    func u16(_ v: UInt16) { var x = v.littleEndian; data.append(Data(bytes: &x, count: 2)) }
    data.append("RIFF".data(using: .ascii)!)
    u32(UInt32(36 + byteCount))
    data.append("WAVEfmt ".data(using: .ascii)!)
    u32(16); u16(1); u16(1); u32(UInt32(sampleRate)); u32(UInt32(sampleRate) * 2); u16(2); u16(16)
    data.append("data".data(using: .ascii)!)
    u32(UInt32(byteCount))
    samples.withUnsafeBufferPointer { data.append(Data(buffer: $0)) }
    return data
  }
}
