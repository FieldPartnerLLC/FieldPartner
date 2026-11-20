import CoreMedia
import CoreVideo
import Foundation
import MWDATCamera
import MWDATCore
import SwiftUI
import UIKit

#if DEBUG
import MWDATMockDevice
#endif

/// Owns the connection to the Meta glasses: registration with the Meta AI app,
/// the device session, the camera stream, and auto-recovery when the link drops,
/// the glasses come off, or they overheat. Every frame is fanned out to:
///  - `onPixelBuffer` (decoded, for LiveKit and the on-screen preview)
///  - `onSampleBuffer` (compressed, for passthrough recording)
@MainActor
final class GlassesManager: ObservableObject {
  enum LinkState: Equatable {
    case notRegistered
    case registering
    case noGlasses
    case connecting
    case connected  // session started, no stream
    case streaming
    case waitingForGlasses  // stream paused or glasses taken off
    case error(String)
  }

  @Published private(set) var registration: RegistrationState
  @Published private(set) var link: LinkState = .noGlasses
  @Published private(set) var hasActiveDevice = false
  @Published private(set) var batteryLevel: Int?
  @Published private(set) var deviceName: String?
  @Published private(set) var lastFrameAt: Date?
  @Published private(set) var framesPerSecond: Double = 0
  @Published var needsCameraPermission = false
  @Published var errorMessage: String?
  /// True while Meta's simulated glasses (MockDeviceKit) stand in for real ones.
  @Published private(set) var simulated = false

  nonisolated(unsafe) var onPixelBuffer: ((CVPixelBuffer) -> Void)?
  nonisolated(unsafe) var onSampleBuffer: ((CMSampleBuffer) -> Void)?

  private let wearables: WearablesInterface
  private let selector: AutoDeviceSelector
  private var session: DeviceSession?
  private var camera: MWDATCamera.Camera?
  private let sessionTokens = ListenerTokenBag()
  private let streamTokens = ListenerTokenBag()
  private var deviceStateToken: AnyListenerToken?
  private var monitorTask: Task<Void, Never>?
  private var registrationTask: Task<Void, Never>?
  private var reconnectTask: Task<Void, Never>?
  private var wantStream = false
  private let decoder = PixelDecoder()
  private var frameCount = 0
  private var fpsWindowStart = Date()

  init(wearables: WearablesInterface = Wearables.shared) {
    self.wearables = wearables
    self.selector = AutoDeviceSelector(wearables: wearables)
    self.registration = wearables.registrationState
    registrationTask = Task { [weak self] in
      guard let self else { return }
      for await state in wearables.registrationStateStream() {
        self.registration = state
        self.refreshLinkFromRegistration()
      }
    }
    monitorTask = Task { [weak self] in
      guard let self else { return }
      for await deviceId in selector.activeDeviceStream() {
        self.hasActiveDevice = deviceId != nil
        self.observeDevice(deviceId)
        if deviceId != nil, self.wantStream, self.session == nil {
          self.startSession()
        }
        self.refreshLinkFromRegistration()
      }
    }
    refreshLinkFromRegistration()
  }

  private func refreshLinkFromRegistration() {
    switch registration {
    case .registered:
      if session == nil, camera == nil, case .notRegistered = link { link = hasActiveDevice ? .connected : .noGlasses }
      if session == nil, !hasActiveDevice { link = .noGlasses }
    case .registering:
      link = .registering
    default:
      link = .notRegistered
    }
  }

  private func observeDevice(_ id: DeviceIdentifier?) {
    deviceStateToken = nil
    guard let id, let device = wearables.deviceForIdentifier(id) else {
      batteryLevel = nil
      deviceName = nil
      return
    }
    deviceName = device.nameOrId()
    deviceStateToken = device.addDeviceStateListener { [weak self] _ in
      Task { @MainActor [weak self] in
        guard let self, let d = self.wearables.deviceForIdentifier(id) else { return }
        self.batteryLevel = d.batteryLevel
      }
    }
  }

  // MARK: - Registration

  func register() {
    Task {
      do {
        try await wearables.startRegistration()
      } catch {
        errorMessage = (error as? RegistrationError)?.description ?? error.localizedDescription
      }
    }
  }

  func unregister() {
    Task {
      try? await wearables.startUnregistration()
    }
  }

  static func handle(url: URL) async {
    guard let comps = URLComponents(url: url, resolvingAgainstBaseURL: false),
      comps.queryItems?.contains(where: { $0.name == "metaWearablesAction" }) == true
    else { return }
    _ = try? await Wearables.shared.handleUrl(url)
  }

  // MARK: - Streaming

  /// Starts the session and the camera stream. Safe to call repeatedly.
  func startStreaming() {
    wantStream = true
    if session == nil { startSession() } else { Task { await beginStreamIfReady() } }
  }

  /// Stops the stream and session. Used at the end of a visit.
  func stopStreaming() {
    wantStream = false
    reconnectTask?.cancel()
    camera?.stop()
    session?.stop()
  }

  /// Privacy pause keeps the session up so resume is instant.
  func pauseCamera() {
    camera?.stop()
  }

  func resumeCamera() {
    wantStream = true
    Task { await beginStreamIfReady() }
  }

  private func startSession() {
    guard registration == .registered else {
      link = .notRegistered
      return
    }
    guard session == nil else { return }
    do {
      let s = try wearables.createSession(deviceSelector: selector)
      session = s
      s.statePublisher.listen { [weak self] state in
        Task { @MainActor in self?.handleSession(state) }
      }.store(in: sessionTokens)
      s.errorPublisher.listen { [weak self] error in
        Task { @MainActor in self?.handleSessionError(error) }
      }.store(in: sessionTokens)
      link = .connecting
      try s.start()
    } catch {
      session = nil
      sessionTokens.clear()
      link = .error(error.localizedDescription)
      scheduleReconnect()
    }
  }

  private func handleSession(_ state: DeviceSessionState) {
    switch state {
    case .started:
      link = .connected
      Task { await beginStreamIfReady() }
    case .paused:
      link = .waitingForGlasses
    case .starting:
      link = .connecting
    case .stopping:
      break
    case .idle, .stopped:
      sessionTokens.clear()
      session = nil
      camera = nil
      streamTokens.clear()
      if wantStream {
        link = .waitingForGlasses
        scheduleReconnect()
      } else {
        link = hasActiveDevice ? .connected : .noGlasses
      }
    @unknown default:
      break
    }
  }

  private func handleSessionError(_ error: DeviceSessionError) {
    errorMessage = error.localizedDescription
  }

  private func beginStreamIfReady() async {
    guard wantStream, camera == nil, let s = session, s.state == .started else { return }
    do {
      if try await wearables.checkPermissionStatus(.camera) != .granted {
        needsCameraPermission = true
        return
      }
    } catch {
      errorMessage = error.localizedDescription
      return
    }
    // hvc1: compressed frames go to the recorder untouched and are decoded once for
    // the live feed. 15 fps leaves more Bluetooth bandwidth per frame, so each frame
    // is sharper for the AI, which reads stills.
    let config = StreamConfiguration(videoCodec: VideoCodec.hvc1, resolution: StreamingResolution.high, frameRate: 15)
    do {
      guard let cam = try s.addCamera(config: config) else {
        scheduleReconnect()
        return
      }
      camera = cam
      attach(cam.stream)
      cam.stream.start()
    } catch {
      errorMessage = error.localizedDescription
      scheduleReconnect()
    }
  }

  /// Opens the Meta AI app to grant camera access, then starts the stream.
  func requestCameraPermission() async {
    needsCameraPermission = false
    do {
      guard try await wearables.requestPermission(.camera) == .granted else {
        errorMessage = "Camera permission was not granted in the Meta AI app."
        return
      }
      await beginStreamIfReady()
    } catch {
      errorMessage = error.localizedDescription
    }
  }

  private func attach(_ stream: MWDATCamera.Stream) {
    stream.statePublisher.listen { [weak self] state in
      Task { @MainActor in self?.handleStream(state) }
    }.store(in: streamTokens)
    stream.errorPublisher.listen { [weak self] error in
      Task { @MainActor in
        guard let self else { return }
        switch error {
        case .thermalHot: self.errorMessage = "Glasses are too hot. Video paused until they cool down."
        case .batteryLow: self.errorMessage = "Glasses battery is low. Video paused."
        default: self.errorMessage = error.localizedDescription
        }
      }
    }.store(in: streamTokens)
    stream.videoFramePublisher.listen { [weak self] frame in
      guard let self else { return }
      let sample = frame.sampleBuffer
      self.onSampleBuffer?(sample)
      if let pb = self.decoder.decode(sample) {
        self.onPixelBuffer?(pb)
      }
      Task { @MainActor [weak self] in self?.countFrame() }
    }.store(in: streamTokens)
  }

  private func handleStream(_ state: StreamState) {
    switch state {
    case .streaming: link = .streaming
    case .paused, .waitingForDevice: link = .waitingForGlasses
    case .starting: link = .connecting
    case .stopping: break
    case .stopped:
      streamTokens.clear()
      camera?.stop()
      camera = nil
      decoder.reset()
      if wantStream, session != nil { scheduleReconnect() } else if session != nil { link = .connected }
    @unknown default: break
    }
  }

  private func countFrame() {
    lastFrameAt = Date()
    frameCount += 1
    let elapsed = Date().timeIntervalSince(fpsWindowStart)
    if elapsed >= 2 {
      framesPerSecond = Double(frameCount) / elapsed
      frameCount = 0
      fpsWindowStart = Date()
    }
  }

  private func scheduleReconnect() {
    guard wantStream else { return }
    reconnectTask?.cancel()
    reconnectTask = Task { [weak self] in
      try? await Task.sleep(nanoseconds: 3_000_000_000)
      guard let self, !Task.isCancelled, self.wantStream else { return }
      if self.session == nil { self.startSession() } else { await self.beginStreamIfReady() }
    }
  }

  // MARK: - Simulated glasses (debug builds only)

  #if DEBUG
  /// Pairs Meta's simulated glasses so the whole pipeline can be tested without real glasses.
  /// `demoVideo` plays the bundled furnace video as the glasses camera (works in the iOS
  /// Simulator, which has no camera). Otherwise the phone's back camera is the feed.
  func enableSimulatedGlasses(demoVideo: Bool = true) {
    MockDeviceKit.shared.enable()
    do {
      let glasses = try MockDeviceKit.shared.pairGlasses(model: .rayBanMeta)
      glasses.powerOn()
      glasses.unfold()
      glasses.don()
      if demoVideo, let url = Bundle.main.url(forResource: "demo-glasses", withExtension: "mp4") {
        glasses.services.camera.setCameraFeed(fileURL: url)
      } else {
        glasses.services.camera.setCameraFeed(cameraFacing: .back)
      }
      simulated = true
    } catch {
      errorMessage = "Simulated glasses failed: \(error.localizedDescription)"
    }
  }
  #endif
}
