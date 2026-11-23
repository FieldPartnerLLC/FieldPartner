import AVFoundation
import Foundation
import LiveKit

/// One LiveKit room per visit. The phone publishes the glasses video and the
/// technician's microphone. The AI agent and, when called, a human expert join the
/// same room. Commands go to the agent on the "fp.cmd" text topic; the agent's
/// events come back on "fp.event".
@MainActor
final class LiveSession: NSObject, ObservableObject {
  enum State: Equatable {
    case idle
    case connecting
    case connected
    case reconnecting
    case failed(String)
  }

  enum AssistantState: String {
    case unknown, idle, listening, thinking, speaking, muted
  }

  @Published private(set) var state: State = .idle
  @Published private(set) var assistant: AssistantState = .unknown
  @Published private(set) var agentPresent = false
  @Published private(set) var expertPresent = false {
    // Boost the glasses video while an expert watches; drop back to save data when they leave.
    didSet { if expertPresent != oldValue { Task { [weak self] in await self?.applyVideoProfileIfNeeded() } } }
  }
  @Published private(set) var expertName: String?
  @Published private(set) var caption: (text: String, fromAI: Bool)?
  @Published private(set) var localVideoTrack: LocalVideoTrack?

  /// Agent events (tool results, hints, safety) for the visit controller.
  var onAgentEvent: (([String: Any]) -> Void)?
  /// Final transcript segments produced by the AI's speech (for the visit log).
  var onAIUtterance: ((String) -> Void)?

  let room = Room(roomOptions: RoomOptions(suspendLocalVideoTracksInBackground: false))
  private final class CapturerBox: @unchecked Sendable {
    var capturer: BufferCapturer?
    var sawFrame = false
  }
  private let capturerBox = CapturerBox()
  private var pendingTrack: LocalVideoTrack?
  private var publishing = false
  /// The glasses track (nil in phone-camera mode) and the profile it is published with now.
  private var glassesTrack: LocalVideoTrack?
  private var publishedProfile: LiveVideoProfile?
  private var switchingProfile = false
  /// Privacy pause or no camera consent: nothing is published or republished while true.
  private var videoMuted = false
  private var usingPhoneCamera = false
  private var handlersRegistered = false
  private var captionClear: Task<Void, Never>?

  override init() {
    super.init()
    room.add(delegate: self)
  }

  // MARK: - Connect

  func connect(ticket: LiveTicket, phoneCamera: Bool) async {
    guard state == .idle || isFailed else { return }
    state = .connecting
    usingPhoneCamera = phoneCamera
    // Glasses: route call audio to their Bluetooth HFP mic and speakers, not the phone speaker.
    AudioManager.shared.isSpeakerOutputPreferred = phoneCamera
    do {
      try await room.connect(url: ticket.url, token: ticket.token)
      try await room.localParticipant.setMicrophone(enabled: true)
      routeToGlassesIfAvailable()
      if phoneCamera {
        try await room.localParticipant.setCamera(enabled: true, captureOptions: CameraCaptureOptions(position: .back))
        localVideoTrack = room.localParticipant.localVideoTracks.compactMap { $0.track as? LocalVideoTrack }.first
      } else {
        let track = await LocalVideoTrack.createBufferTrack(name: "glasses", source: .camera)
        capturerBox.sawFrame = false
        capturerBox.capturer = track.capturer as? BufferCapturer
        try await track.start()
        localVideoTrack = track
        glassesTrack = track
        pendingTrack = track
        // Publishing waits for the first frame: the buffer track needs real dimensions,
        // and the first frame over Bluetooth can take several seconds.
        if capturerBox.sawFrame { await publishPending() }
      }
      await registerHandlers()
      state = .connected
      refreshParticipants()
      await applyVideoProfileIfNeeded()
    } catch {
      state = .failed(error.localizedDescription)
      await room.disconnect()
    }
  }

  func disconnect() async {
    capturerBox.capturer = nil
    pendingTrack = nil
    localVideoTrack = nil
    glassesTrack = nil
    publishedProfile = nil
    switchingProfile = false
    videoMuted = false
    await room.disconnect()
    state = .idle
    agentPresent = false
    expertPresent = false
    assistant = .unknown
  }

  private var isFailed: Bool {
    if case .failed = state { return true }
    return false
  }

  private func routeToGlassesIfAvailable() {
    let session = AVAudioSession.sharedInstance()
    guard !usingPhoneCamera,
      let hfp = session.availableInputs?.first(where: { $0.portType == .bluetoothHFP }),
      !session.currentRoute.inputs.contains(where: { $0.portType == .bluetoothHFP })
    else { return }
    try? session.setPreferredInput(hfp)
  }

  // MARK: - Video

  /// Called for every decoded glasses frame, from any thread.
  nonisolated func push(_ pixelBuffer: CVPixelBuffer) {
    capturerBox.capturer?.capture(pixelBuffer)
    if !capturerBox.sawFrame {
      capturerBox.sawFrame = true
      Task { @MainActor in await self.publishPending() }
    }
  }

  private func publishPending() async {
    // While muted the track stays unpublished, so the AI and expert get no video at all.
    guard let track = pendingTrack, state == .connected || state == .connecting, !publishing, !videoMuted else { return }
    publishing = true
    let profile = LiveVideoProfile.forRoom(expertPresent: expertPresent)
    do {
      _ = try await room.localParticipant.publish(videoTrack: track, options: Self.publishOptions(profile))
      pendingTrack = nil
      publishedProfile = profile
    } catch {
      NSLog("[Live] video publish failed, retrying on next frame: %@", error.localizedDescription)
      capturerBox.sawFrame = false
    }
    publishing = false
    // An expert may have joined or left while this publish ran.
    await applyVideoProfileIfNeeded()
  }

  private static func publishOptions(_ profile: LiveVideoProfile) -> VideoPublishOptions {
    VideoPublishOptions(
      encoding: VideoEncoding(maxBitrate: profile.maxBitrate, maxFps: profile.maxFps),
      simulcast: false,
      degradationPreference: .maintainResolution)
  }

  /// LiveKit has no public call to change a published track's encoding, so a profile change
  /// unpublishes the glasses track (which stops it), restarts it, and lets the next glasses frame
  /// publish it again with the new profile (`publishPending`). The AI and expert see a short gap.
  private func applyVideoProfileIfNeeded() async {
    guard !usingPhoneCamera, !switchingProfile, !publishing, !videoMuted, state == .connected,
      let track = glassesTrack, let current = publishedProfile
    else { return }
    guard LiveVideoProfile.forRoom(expertPresent: expertPresent) != current,
      let publication = room.localParticipant.localVideoTracks.first(where: { $0.track === track })
    else { return }
    switchingProfile = true
    defer { switchingProfile = false }
    do {
      try await room.localParticipant.unpublish(publication: publication)
    } catch {
      NSLog("[Live] video unpublish for profile switch failed: %@", error.localizedDescription)
      return
    }
    publishedProfile = nil
    do { try await track.start() } catch {
      NSLog("[Live] video restart after unpublish failed: %@", error.localizedDescription)
    }
    // The next glasses frame publishes the track again with the profile the room needs then.
    capturerBox.sawFrame = false
    pendingTrack = track
  }

  /// Privacy pause: stop sending video to the room (AI and expert see nothing).
  func setVideoMuted(_ muted: Bool) async {
    videoMuted = muted
    for pub in room.localParticipant.localVideoTracks {
      if muted { try? await pub.mute() } else { try? await pub.unmute() }
    }
    guard !muted else { return }
    // A frame that arrived while muted could not publish; publish now that video is allowed.
    if capturerBox.sawFrame { await publishPending() }
    await applyVideoProfileIfNeeded()
  }

  func setMicMuted(_ muted: Bool) async {
    try? await room.localParticipant.setMicrophone(enabled: !muted)
  }

  // MARK: - Agent commands

  func send(_ command: [String: Any]) async {
    guard state == .connected || state == .reconnecting,
      let data = try? JSONSerialization.data(withJSONObject: command),
      let json = String(data: data, encoding: .utf8)
    else { return }
    do {
      _ = try await room.localParticipant.sendText(json, for: "fp.cmd")
    } catch {
      NSLog("[Live] command send failed: %@", error.localizedDescription)
    }
  }

  private func registerHandlers() async {
    guard !handlersRegistered else { return }
    handlersRegistered = true
    try? await room.registerTextStreamHandler(for: "fp.event") { [weak self] reader, _ in
      let text = try await reader.readAll()
      guard let data = text.data(using: .utf8),
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
      else { return }
      await self?.handleAgentEvent(obj)
    }
    try? await room.registerTextStreamHandler(for: "lk.transcription") { [weak self] reader, identity in
      let fromAI = identity.stringValue.hasPrefix("agent")
      var text = ""
      for try await chunk in reader {
        text += chunk
        await self?.showCaption(text, fromAI: fromAI)
      }
      if fromAI, !text.isEmpty { await self?.onAIUtterance?(text) }
    }
  }

  private func handleAgentEvent(_ obj: [String: Any]) {
    if (obj["type"] as? String) == "assistant_state", let s = obj["state"] as? String {
      assistant = AssistantState(rawValue: s) ?? .unknown
    }
    if (obj["type"] as? String) == "expert", let present = obj["present"] as? Bool {
      expertPresent = present
    }
    onAgentEvent?(obj)
  }

  private func showCaption(_ text: String, fromAI: Bool) {
    caption = (text, fromAI)
    captionClear?.cancel()
    captionClear = Task { [weak self] in
      try? await Task.sleep(nanoseconds: 5_000_000_000)
      if !Task.isCancelled { self?.caption = nil }
    }
  }

  private func refreshParticipants() {
    let remotes = room.remoteParticipants.values
    agentPresent = remotes.contains { $0.isAgent || ($0.identity?.stringValue.hasPrefix("agent") ?? false) }
    let expert = remotes.first { $0.identity?.stringValue.hasPrefix("expert-") ?? false }
    expertPresent = expert != nil
    expertName = expert?.name
  }
}

extension LiveSession: RoomDelegate {
  nonisolated func room(_ room: Room, participantDidConnect participant: RemoteParticipant) {
    Task { @MainActor in self.refreshParticipants() }
  }

  nonisolated func room(_ room: Room, participantDidDisconnect participant: RemoteParticipant) {
    Task { @MainActor in self.refreshParticipants() }
  }

  nonisolated func room(_ room: Room, didStartReconnectWithMode reconnectMode: ReconnectMode) {
    Task { @MainActor in self.state = .reconnecting }
  }

  nonisolated func room(_ room: Room, didCompleteReconnectWithMode reconnectMode: ReconnectMode) {
    Task { @MainActor in
      self.state = .connected
      self.refreshParticipants()
      await self.applyVideoProfileIfNeeded()
    }
  }

  nonisolated func room(_ room: Room, didDisconnectWithError error: LiveKitError?) {
    Task { @MainActor in
      self.handlersRegistered = false
      self.agentPresent = false
      self.expertPresent = false
      if let error {
        self.state = .failed(error.localizedDescription)
      } else if self.state != .idle {
        self.state = .idle
      }
    }
  }
}
