import AVFoundation
import CoreLocation
import Foundation
import LiveKit
import SwiftUI

/// Runs one visit end to end: glasses, recording, transcript, the AI partner,
/// the live call, consent, escalation, and the hand-off to the report.
@MainActor
final class VisitController: ObservableObject {
  enum Phase: Equatable {
    case preparing
    case preVisit  // recording, waiting for customer consent
    case active  // consent given, full assistance
    case noCamera  // customer declined video (audio only or no recording)
    case ending
    case ended
    case declined
  }

  struct Notice: Identifiable, Equatable {
    enum Kind { case info, hint, safety, tool, escalation, error }
    let id = UUID()
    let kind: Kind
    let title: String
    let detail: String
    let at = Date()
  }

  struct Preflight: Equatable {
    var glasses: Bool
    var glassesBattery: Int?
    var phoneBattery: Int
    var freeStorageGB: Double
    var server: Bool
    var live: Bool
    var speech: Bool
  }

  // Published UI state
  @Published private(set) var phase: Phase = .preparing
  @Published private(set) var recording = false
  @Published private(set) var cameraPaused = false
  @Published private(set) var consentChoice: String?
  @Published private(set) var notices: [Notice] = []
  @Published private(set) var transcript: [String] = []
  @Published private(set) var livePartial = ""
  @Published private(set) var readings: [(name: String, value: String, unit: String)] = []
  @Published private(set) var pendingHints = 0
  @Published private(set) var safetyAlert: String?
  @Published private(set) var escalation: String = "none"  // none, requested, joined, unanswered
  @Published private(set) var estimateDraft: [String: String]?
  @Published private(set) var queuedEvents = 0
  @Published private(set) var preflight: Preflight?
  @Published var errorBanner: String?

  let visit: Visit
  let job: Job
  let live = LiveSession()
  let glasses: GlassesManager
  private let api: APIClient
  private let company: CompanyInfo?
  private let settings = AppSettings.shared
  private let events: EventQueue
  private let transcriptEngine = TranscriptEngine()
  private let micTap = LiveMicTap()
  private let localMic = LocalMicCapture()
  private let videoRecorder: SegmentRecorder
  private let audioRecorder: AudioFileRecorder
  private let clip = ClipButton()
  private let speech = AVSpeechSynthesizer()
  private let location = OneShotLocation()
  private var parser: CommandParser
  private var flushTask: Task<Void, Never>?
  private var liveRetryTask: Task<Void, Never>?
  private var wakeChimedForUtterance = false
  private var started = false
  let mediaDirectory: URL

  init(visit: Visit, job: Job, api: APIClient, company: CompanyInfo?, glasses: GlassesManager) {
    self.visit = visit
    self.job = job
    self.api = api
    self.company = company
    self.glasses = glasses
    self.events = EventQueue(visitId: visit.id)
    let wake = AppSettings.shared.wakePhraseOverride.isEmpty ? (company?.wake_phrase ?? "hey dispatch") : AppSettings.shared.wakePhraseOverride
    self.parser = CommandParser(wakePhrase: wake)
    let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("FieldPartner/visits/\(visit.id)", isDirectory: true)
    self.mediaDirectory = dir
    self.videoRecorder = SegmentRecorder(directory: dir, prefix: "visit")
    self.audioRecorder = AudioFileRecorder(directory: dir, prefix: "visit")
    if visit.consent_choice != nil {
      consentChoice = visit.consent_choice
      phase = visit.consent_choice == "video_audio" ? .active : (visit.consent_choice == "declined_service" ? .declined : .noCamera)
    }
  }

  var wakePhrase: String { parser.wakePhrase }

  // MARK: - Start

  func checkPreflight() async {
    UIDevice.current.isBatteryMonitoringEnabled = true
    let battery = Int((UIDevice.current.batteryLevel < 0 ? 1 : UIDevice.current.batteryLevel) * 100)
    let values = try? URL(fileURLWithPath: NSHomeDirectory()).resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
    let free = Double(values?.volumeAvailableCapacityForImportantUsage ?? 0) / 1e9
    var serverOK = false
    do {
      _ = try await api.me()
      serverOK = true
    } catch {}
    let speechOK = await TranscriptEngine.requestPermission()
    preflight = Preflight(
      glasses: settings.usePhoneCamera || glasses.hasActiveDevice,
      glassesBattery: glasses.batteryLevel,
      phoneBattery: battery,
      freeStorageGB: free,
      server: serverOK,
      live: true,
      speech: speechOK)
  }

  func start() async {
    guard !started else { return }
    started = true
    _ = await AVAudioApplication.requestRecordPermission()
    _ = await TranscriptEngine.requestPermission()

    // Glasses frames fan out to the live call and the recorder.
    glasses.onPixelBuffer = { [weak live] pb in live?.push(pb) }
    glasses.onSampleBuffer = { [weak self] sample in self?.videoRecorder.append(sample) }
    if !settings.usePhoneCamera { glasses.startStreaming() }

    // Audio: the call's mic when connected, a local capture otherwise.
    micTap.onBuffer = { [weak self] buf in
      self?.transcriptEngine.append(buf)
      self?.audioRecorder.append(buf)
    }
    localMic.onBuffer = micTap.onBuffer
    AudioManager.shared.add(localAudioRenderer: micTap)

    transcriptEngine.onPartial = { [weak self] text in Task { @MainActor in self?.handlePartial(text) } }
    transcriptEngine.onFinal = { [weak self] text in Task { @MainActor in self?.handleFinal(text) } }
    transcriptEngine.start(locale: settings.language)

    videoRecorder.onSegmentFinished = { [weak self] seg in
      guard let self else { return }
      Task { await UploadManager.shared.enqueue(visitId: self.visit.id, file: seg.url, kind: "video", contentType: "video/quicktime") }
    }
    audioRecorder.onSegmentFinished = { [weak self] url in
      guard let self else { return }
      Task { await UploadManager.shared.enqueue(visitId: self.visit.id, file: url, kind: "audio", contentType: "audio/mp4") }
    }

    live.onAgentEvent = { [weak self] e in self?.handleAgentEvent(e) }
    live.onAIUtterance = { [weak self] text in
      self?.transcript.append("AI: \(text)")
    }

    clip.onAction = { [weak self] action in self?.handleClip(action) }
    clip.start(in: UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first)

    startRecording()
    if phase == .preparing { phase = .preVisit }
    log("state", ["state": .string("pre_visit")], actor: "system")
    startFlushLoop()
    await connectLive()
    announce("Recording started. Your partner is standing by. Get consent at the door.")
  }

  private func startRecording() {
    if consentChoice != "no_recording" {
      if consentChoice != "audio_only" { videoRecorder.start() }
      audioRecorder.start()
      recording = true
      Earcon.recordingOn.play()
    }
  }

  private func connectLive() async {
    do {
      let ticket = try await api.liveTicket(visitId: visit.id)
      localMic.stop()
      await live.connect(ticket: ticket, phoneCamera: settings.usePhoneCamera)
      if case .failed(let msg) = live.state { throw APIError.server(status: 0, code: "live", message: msg) }
      await live.send(["type": "camera", "allowed": cameraAllowed])
      if !cameraAllowed { await live.setVideoMuted(true) }
    } catch {
      // Keep working offline: local mic for transcript and recording, retry the call.
      try? localMic.start()
      notify(.error, "Partner offline", (error as? APIError)?.localizedDescription ?? error.localizedDescription)
      scheduleLiveRetry()
    }
  }

  private func scheduleLiveRetry() {
    liveRetryTask?.cancel()
    liveRetryTask = Task { [weak self] in
      try? await Task.sleep(nanoseconds: 15_000_000_000)
      guard let self, !Task.isCancelled, self.phase != .ended, self.phase != .ending else { return }
      if self.live.state != .connected { await self.connectLive() }
    }
  }

  private var cameraAllowed: Bool {
    !cameraPaused && (consentChoice == nil || consentChoice == "video_audio")
  }

  // MARK: - Transcript and voice commands

  private func handlePartial(_ text: String) {
    livePartial = text
    if !wakeChimedForUtterance, parser.wakeHeard(inPartial: text) {
      wakeChimedForUtterance = true
      Earcon.wake.play()
    }
  }

  private func handleFinal(_ text: String) {
    livePartial = ""
    wakeChimedForUtterance = false
    transcript.append(text)
    if transcript.count > 400 { transcript.removeFirst(transcript.count - 400) }
    log("transcript", ["speaker": .string("room"), "text": .string(text)], actor: "tech")
    guard let command = parser.parse(finalUtterance: text) else { return }
    Task { await perform(command) }
  }

  func perform(_ command: VoiceCommand) async {
    switch command {
    case .ask(let question):
      log("wake", ["text": .string(question)])
      guard live.state == .connected else {
        announce("Your partner is offline right now. Keep working, I'm still recording.")
        return
      }
      if question.isEmpty {
        await live.send(["type": "listen"])
      } else {
        await live.send(["type": "ask", "text": question])
      }
    case .stop:
      await live.send(["type": "stop"])
    case .continueLast:
      await live.send(["type": "continue"])
    case .pauseCamera:
      await setCameraPaused(true)
    case .resumeCamera:
      await setCameraPaused(false)
    case .playHint:
      pendingHints = max(0, pendingHints - 1)
      await live.send(["type": "hint_play"])
    case .acknowledge:
      if let alert = safetyAlert {
        log("safety", ["level": .string("acknowledged"), "reason": .string(alert)])
        safetyAlert = nil
      }
    case .callExpert:
      await escalate(reason: "Technician asked for help by voice")
    case .stopRecording:
      await revokeRecording()
    case .disputed(let text):
      log("disputed", ["text": .string(text)])
      await live.send(["type": "disputed", "text": text])
    }
  }

  private func handleClip(_ action: ClipButton.Action) {
    Task {
      switch action {
      case .talk:
        Earcon.wake.play()
        await perform(.ask(""))
      case .stop:
        Earcon.close.play()
        await perform(.stop)
      case .toggleCamera:
        await setCameraPaused(!cameraPaused)
      }
    }
  }

  /// Push to talk from the on-screen button.
  func talkButton() {
    Earcon.wake.play()
    Task { await perform(.ask("")) }
  }

  // MARK: - Agent events

  private func handleAgentEvent(_ e: [String: Any]) {
    guard let type = e["type"] as? String else { return }
    switch type {
    case "assistant_state":
      if (e["state"] as? String) == "idle", (e["reason"] as? String) == "follow_up_window_closed" { Earcon.close.play() }
    case "hint":
      pendingHints += 1
      Earcon.hint.play(volume: 0.4)
      notify(.hint, "Tip waiting", (e["reason"] as? String) ?? "")
    case "safety":
      let reason = (e["reason"] as? String) ?? "Hazard"
      safetyAlert = reason
      Earcon.alert.play(volume: 1.0)
      UINotificationFeedbackGenerator().notificationOccurred(.error)
      notify(.safety, "STOP", reason)
    case "tool":
      let name = (e["name"] as? String) ?? ""
      let data = (e["data"] as? [String: Any]) ?? [:]
      switch name {
      case "save_reading":
        readings.append((name: "\(data["name"] ?? "")", value: "\(data["value"] ?? "")", unit: "\(data["unit"] ?? "")"))
        notify(.tool, "Reading saved", "\(data["name"] ?? ""): \(data["value"] ?? "") \(data["unit"] ?? "")")
      case "save_equipment":
        notify(.tool, "Equipment saved", data.values.map { "\($0)" }.joined(separator: " "))
      case "draft_estimate":
        estimateDraft = data.mapValues { "\($0)" }
        notify(.tool, "Estimate draft ready", (data["repair"] as? String) ?? "")
      case "add_note":
        notify(.tool, "Note saved", (data["text"] as? String) ?? "")
      case "senior_expert":
        switch data["status"] as? String {
        case "asking": notify(.info, "Checking with the senior expert", (data["question"] as? String) ?? "")
        case "answered": notify(.tool, "Senior expert", (data["answer"] as? String) ?? "")
        default: notify(.info, "Senior expert not available", "Using general guidance.")
        }
      default: break
      }
    case "escalation":
      escalation = (e["status"] as? String) ?? escalation
      if let who = e["paged"] as? String { notify(.escalation, "Paging \(who)", (e["reason"] as? String) ?? "") }
    case "expert":
      let present = (e["present"] as? Bool) ?? false
      if present {
        escalation = "joined"
        announce("Your expert is on the line.")
      }
    case "watcher":
      notify(.error, "Hazard watch offline", "Background safety check can't reach the AI. Keep using your meters.")
    default:
      break
    }
  }

  // MARK: - Consent

  func submitConsent(choice: String, signerName: String, signerRole: String, privateAreasOk: Bool, signature: UIImage?, language: String) async -> Bool {
    let coord = await location.current()
    let body = APIClient.ConsentBody(
      kind: "service_and_recording", choice: choice, privateAreasOk: privateAreasOk,
      signerName: signerName.isEmpty ? nil : signerName, signerRole: signerRole, language: language,
      lat: coord?.latitude, lng: coord?.longitude, textVersion: ConsentText.version,
      signaturePngBase64: signature?.pngData()?.base64EncodedString())
    do {
      _ = try await api.consent(visitId: visit.id, body: body)
    } catch {
      errorBanner = "Consent not saved: \(error.localizedDescription) Try again."
      return false
    }
    consentChoice = choice
    switch choice {
    case "video_audio":
      phase = .active
      await live.send(["type": "camera", "allowed": true])
      await live.setVideoMuted(false)
      announce("Consent saved. Video on.")
    case "audio_only":
      phase = .noCamera
      await videoRecorder.stop()
      await live.setVideoMuted(true)
      await live.send(["type": "camera", "allowed": false])
      announce("Consent saved. Audio only.")
    case "no_recording":
      phase = .noCamera
      await videoRecorder.stop()
      audioRecorder.stop()
      recording = false
      await live.setVideoMuted(true)
      await live.send(["type": "camera", "allowed": false])
      Earcon.recordingOff.play()
      announce("Consent saved. Recording off.")
    case "declined_service":
      phase = .declined
      await end()
    default:
      break
    }
    return true
  }

  func revokeRecording() async {
    _ = try? await api.consent(
      visitId: visit.id,
      body: APIClient.ConsentBody(
        kind: "recording_revoked", choice: nil, privateAreasOk: nil, signerName: nil, signerRole: nil,
        language: "en", lat: nil, lng: nil, textVersion: ConsentText.version, signaturePngBase64: nil))
    consentChoice = "no_recording"
    phase = .noCamera
    await videoRecorder.stop()
    audioRecorder.stop()
    recording = false
    await live.setVideoMuted(true)
    await live.send(["type": "camera", "allowed": false])
    Earcon.recordingOff.play()
    announce("Recording is off.")
  }

  // MARK: - Camera privacy

  func setCameraPaused(_ paused: Bool) async {
    guard paused != cameraPaused else { return }
    cameraPaused = paused
    if paused {
      await videoRecorder.pause()
      await live.setVideoMuted(true)
      log("pause", [:])
      Earcon.recordingOff.play()
    } else {
      if consentChoice == nil || consentChoice == "video_audio" {
        videoRecorder.start()
        await live.setVideoMuted(false)
      }
      log("resume", [:])
      Earcon.recordingOn.play()
    }
    await live.send(["type": "camera", "allowed": cameraAllowed])
  }

  // MARK: - Escalation

  func escalate(reason: String) async {
    escalation = "requested"
    announce("Calling your expert.")
    do {
      let r = try await api.escalate(visitId: visit.id, reason: reason)
      escalation = r.status
      if let who = r.paged { notify(.escalation, "Paging \(who)", reason) }
    } catch {
      escalation = "none"
      notify(.error, "Couldn't page the expert", "Call them directly. \(error.localizedDescription)")
    }
  }

  // MARK: - Readings by hand

  func addReading(name: String, value: String, unit: String) {
    readings.append((name: name, value: value, unit: unit))
    log("reading", ["name": .string(name), "value": .string(value), "unit": .string(unit)])
  }

  func addNote(_ text: String) {
    log("note", ["text": .string(text)])
  }

  // MARK: - End

  func end() async {
    guard phase != .ending, phase != .ended else { return }
    let wasDeclined = phase == .declined
    phase = .ending
    await live.send(["type": "end"])
    transcriptEngine.stop()
    await videoRecorder.stop()
    audioRecorder.stop()
    localMic.stop()
    clip.stop()
    recording = false
    Earcon.recordingOff.play()
    log("state", ["state": .string("wrap_up")], actor: "system")
    await saveTranscriptFile()
    await flushEvents()
    do {
      _ = try await api.endVisit(visitId: visit.id)
    } catch {
      notify(.error, "Visit end not sent yet", "It will retry. \(error.localizedDescription)")
    }
    glasses.stopStreaming()
    await live.disconnect()
    flushTask?.cancel()
    liveRetryTask?.cancel()
    phase = wasDeclined ? .declined : .ended
    Task { await UploadManager.shared.processQueue() }
  }

  private func saveTranscriptFile() async {
    let url = mediaDirectory.appendingPathComponent("transcript.txt")
    try? transcript.joined(separator: "\n").data(using: .utf8)?.write(to: url)
    await UploadManager.shared.enqueue(visitId: visit.id, file: url, kind: "transcript", contentType: "text/plain")
  }

  // MARK: - Event plumbing

  private func log(_ type: String, _ data: [String: JSONValue], actor: String = "tech") {
    let e = VisitEvent(type: type, actor: actor, data: data)
    Task { await events.add(e) }
  }

  private func startFlushLoop() {
    flushTask = Task { [weak self] in
      while !Task.isCancelled {
        try? await Task.sleep(nanoseconds: 3_000_000_000)
        await self?.flushEvents()
      }
    }
  }

  private func flushEvents() async {
    let api = self.api
    let visitId = visit.id
    await events.flush { batch in try await api.postEvents(visitId: visitId, events: batch) }
    queuedEvents = await events.count
  }

  private func notify(_ kind: Notice.Kind, _ title: String, _ detail: String) {
    notices.insert(Notice(kind: kind, title: title, detail: detail), at: 0)
    if notices.count > 50 { notices.removeLast() }
  }

  /// Short spoken status in the glasses. Never used while the AI is talking.
  func announce(_ text: String) {
    guard live.assistant != .speaking else { return }
    let u = AVSpeechUtterance(string: text)
    u.rate = 0.52
    u.volume = Float(settings.aiVoiceVolume)
    speech.speak(u)
  }
}

/// One-shot location for the consent record. Returns nil quickly if denied or slow.
final class OneShotLocation: NSObject, CLLocationManagerDelegate {
  private let manager = CLLocationManager()
  private var continuation: CheckedContinuation<CLLocationCoordinate2D?, Never>?

  @MainActor
  func current() async -> CLLocationCoordinate2D? {
    manager.delegate = self
    if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
    guard manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways else { return nil }
    return await withCheckedContinuation { cont in
      self.continuation = cont
      manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
      manager.requestLocation()
      DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
        self?.continuation?.resume(returning: nil)
        self?.continuation = nil
      }
    }
  }

  func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    continuation?.resume(returning: locations.last?.coordinate)
    continuation = nil
  }

  func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    continuation?.resume(returning: nil)
    continuation = nil
  }
}
