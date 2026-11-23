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
///
/// Keeps listening through route changes: when the glasses connect or drop, or a phone call
/// interrupts, AVAudioEngine stops itself and its input format changes. Without a restart the
/// transcript and the wake phrase would go silent for the rest of the visit.
final class LocalMicCapture: @unchecked Sendable {
  enum CaptureError: LocalizedError {
    case noInput
    var errorDescription: String? { "No microphone is available." }
  }

  private let engine = AVAudioEngine()
  // Recursive: AVAudioEngine can post a configuration change while start() holds the lock.
  private let lock = NSRecursiveLock()
  private(set) var running = false
  private var observers: [NSObjectProtocol] = []
  var onBuffer: ((AVAudioPCMBuffer) -> Void)?

  func start() throws {
    lock.lock(); defer { lock.unlock() }
    guard !running else { return }
    let session = AVAudioSession.sharedInstance()
    // HFP is the glasses' mic and speakers. defaultToSpeaker only applies when nothing else is
    // connected, so with no glasses the earcons play on the loudspeaker, not the earpiece.
    try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker])
    try session.setActive(true)
    Self.preferGlassesMic(session)
    try startEngine()
    running = true
    observe()
  }

  func stop() {
    lock.lock(); defer { lock.unlock() }
    guard running else { return }
    running = false
    observers.forEach { NotificationCenter.default.removeObserver($0) }
    observers = []
    engine.inputNode.removeTap(onBus: 0)
    engine.stop()
  }

  /// Routes input to the glasses' Bluetooth mic if they are connected and not already in use.
  static func preferGlassesMic(_ session: AVAudioSession = .sharedInstance()) {
    guard let hfp = session.availableInputs?.first(where: { $0.portType == .bluetoothHFP }),
      !session.currentRoute.inputs.contains(where: { $0.portType == .bluetoothHFP })
    else { return }
    try? session.setPreferredInput(hfp)
  }

  private func startEngine() throws {
    let input = engine.inputNode
    let format = input.outputFormat(forBus: 0)
    // A zero format (no input route, simulator without a mic) makes installTap raise an
    // Objective-C exception, which would crash the app. Report it instead.
    guard format.sampleRate > 0, format.channelCount > 0 else { throw CaptureError.noInput }
    input.removeTap(onBus: 0)
    input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
      self?.onBuffer?(buffer)
    }
    engine.prepare()
    try engine.start()
  }

  private func restart(reason: String) {
    lock.lock(); defer { lock.unlock() }
    guard running else { return }
    engine.inputNode.removeTap(onBus: 0)
    engine.stop()
    do {
      try AVAudioSession.sharedInstance().setActive(true)
      Self.preferGlassesMic()
      try startEngine()
    } catch {
      NSLog("[LocalMic] restart after %@ failed: %@", reason, error.localizedDescription)
    }
  }

  private func observe() {
    let nc = NotificationCenter.default
    observers.append(nc.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil) { [weak self] _ in
      self?.restart(reason: "configuration change")
    })
    observers.append(nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { [weak self] note in
      guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
        AVAudioSession.InterruptionType(rawValue: raw) == .ended
      else { return }
      self?.restart(reason: "interruption")
    })
    observers.append(nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil) { note in
      guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
        AVAudioSession.RouteChangeReason(rawValue: raw) == .newDeviceAvailable
      else { return }
      // Glasses just connected: move the mic to them. The engine's configuration-change
      // notification then restarts capture in the new format.
      Self.preferGlassesMic()
    })
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
  /// After a failed open, wait before trying again instead of retrying on every buffer.
  private var openBlockedUntil = Date.distantPast
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
        guard Date() >= self.openBlockedUntil else { return }
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

  /// AAC bit rate for a sample rate. Bluetooth HFP runs at 16 kHz (8 kHz on older headsets),
  /// where 64 kbps is above what the AAC encoder accepts and the file would fail to open.
  static func aacBitRate(sampleRate: Double, channels: AVAudioChannelCount) -> Int {
    let perChannel = min(64_000, max(16_000, Int(sampleRate * 2)))
    return perChannel * Int(max(1, channels))
  }

  private func open(format: AVAudioFormat) {
    index += 1
    let url = directory.appendingPathComponent(String(format: "%@-audio-%03d.m4a", prefix, index))
    try? FileManager.default.removeItem(at: url)
    var settings: [String: Any] = [
      AVFormatIDKey: kAudioFormatMPEG4AAC,
      AVSampleRateKey: format.sampleRate,
      AVNumberOfChannelsKey: format.channelCount,
      AVEncoderBitRateKey: Self.aacBitRate(sampleRate: format.sampleRate, channels: format.channelCount),
    ]
    do {
      file = try AVAudioFile(forWriting: url, settings: settings, commonFormat: format.commonFormat, interleaved: format.isInterleaved)
    } catch {
      // Let the encoder pick its own bit rate before giving up.
      settings.removeValue(forKey: AVEncoderBitRateKey)
      file = try? AVAudioFile(forWriting: url, settings: settings, commonFormat: format.commonFormat, interleaved: format.isInterleaved)
      if file == nil { NSLog("[AudioFile] open failed: %@", error.localizedDescription) }
    }
    if file != nil {
      fileURL = url
      fileFormat = format
      openedAt = Date()
    } else {
      openBlockedUntil = Date().addingTimeInterval(5)
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
