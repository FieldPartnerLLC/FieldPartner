import CoreImage
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
  /// A still from the live stream about once a second while `previewEnabled` (first-run setup).
  @Published private(set) var previewImage: UIImage?
  /// Frames received since the app started. Setup uses it to prove video arrives.
  @Published private(set) var totalFrames = 0
  nonisolated(unsafe) var previewEnabled = false
  nonisolated(unsafe) private var lastPreviewAt = Date.distantPast
  private let ciContext = CIContext()

  nonisolated(unsafe) var onPixelBuffer: ((CVPixelBuffer) -> Void)?
  nonisolated(unsafe) var onSampleBuffer: ((CMSampleBuffer) -> Void)?

  /// Set at launch by FieldPartnerApp. When Meta's SDK fails to configure, the app still runs
  /// (phone camera, AI, reports) and the glasses show as unavailable instead of crashing.
  nonisolated(unsafe) static var sdkReady = false
  nonisolated(unsafe) static var sdkError: String?

  private let wearables: WearablesInterface?
  private let selector: AutoDeviceSelector?
  private var session: DeviceSession?
  private var camera: MWDATCamera.Camera?
  private let sessionTokens = ListenerTokenBag()
  private let streamTokens = ListenerTokenBag()
  /// Battery and compatibility listeners for the active device. A token set to nil does not
  /// cancel its listener (cancel is async), so they live in a bag that is cleared on change.
  private let deviceTokens = ListenerTokenBag()
  /// Privacy pause: the camera stays off until resumeCamera(), even though wantStream is true.
  private var cameraPaused = false
  private var monitorTask: Task<Void, Never>?
  private var registrationTask: Task<Void, Never>?
  private var reconnectTask: Task<Void, Never>?
  private var wantStream = false
  private let decoder = PixelDecoder()
  private var frameCount = 0
  private var fpsWindowStart = Date()

  init(wearables: WearablesInterface? = GlassesManager.sdkReady ? Wearables.shared : nil) {
    self.wearables = wearables
    guard let wearables else {
      self.selector = nil
      self.registration = .unavailable
      self.link = .error("Glasses are unavailable on this phone: \(GlassesManager.sdkError ?? "the Meta SDK did not start").")
      return
    }
    self.selector = AutoDeviceSelector(wearables: wearables)
    self.registration = wearables.registrationState
    registrationTask = Task { [weak self] in
      guard let self else { return }
      for await state in wearables.registrationStateStream() {
        self.registration = state
        self.refreshLinkFromRegistration()
        // Registration can finish after a visit asked for video (Meta AI approval, or a
        // Meta AI-initiated registration). Start the session instead of waiting for a device change.
        if state == .registered, self.wantStream, self.session == nil, self.hasActiveDevice {
          self.startSession()
        }
      }
    }
    monitorTask = Task { [weak self] in
      guard let self else { return }
      guard let selector = self.selector else { return }
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
    deviceTokens.clear()
    guard let id, let wearables, let device = wearables.deviceForIdentifier(id) else {
      batteryLevel = nil
      deviceName = nil
      return
    }
    deviceName = device.nameOrId()
    device.addDeviceStateListener { [weak self] state in
      Task { @MainActor [weak self] in self?.batteryLevel = state.batteryLevel }
    }.store(in: deviceTokens)
    showCompatibility(device.compatibility())
    device.addCompatibilityListener { [weak self] compat in
      Task { @MainActor [weak self] in self?.showCompatibility(compat) }
    }.store(in: deviceTokens)
  }

  /// Glasses firmware or the glasses-side app often needs an update before the first stream;
  /// nothing streams until it is done, so say so instead of failing silently.
  private func showCompatibility(_ compat: Compatibility) {
    switch compat {
    case .deviceUpdateRequired:
      errorMessage = "Your glasses need a software update. Open the Meta AI app, update the glasses, then try again."
    case .sdkUpdateRequired:
      errorMessage = "This FieldPartner build is too old for your glasses. Install the latest FieldPartner build."
    default:
      break
    }
  }

  // MARK: - Registration

  func register() {
    guard let wearables else {
      errorMessage = "Glasses are unavailable on this phone: \(GlassesManager.sdkError ?? "the Meta SDK did not start")."
      return
    }
    Task {
      do {
        try await wearables.startRegistration()
      } catch {
        if let msg = Self.message(for: error) { errorMessage = msg }
      }
    }
  }

  func unregister() {
    Task {
      try? await wearables?.startUnregistration()
    }
  }

  /// Meta AI calls back to fieldpartner:// after registration and permission requests. Meta's guidance
  /// is to hand every callback URL to the SDK (it returns false for URLs that are not its own), so
  /// no query-parameter filter here: a filter that misses Meta's format would drop the callback and
  /// leave registration stuck. Registration started from inside the Meta AI app arrives as a
  /// RegistrationRequest, which FieldPartner always continues.
  static func handle(url: URL) async {
    guard sdkReady else { return }
    do {
      _ = try await Wearables.shared.handleUrl(url) { request in
        Task {
          do {
            try await request.continueRegistration()
          } catch {
            NSLog("[Glasses] Meta AI registration request failed: %@", String(describing: error))
          }
        }
      }
    } catch {
      NSLog("[Glasses] handleUrl failed: %@", String(describing: error))
    }
  }

  // MARK: - Streaming

  /// Starts the session and the camera stream. Safe to call repeatedly.
  func startStreaming() {
    wantStream = true
    cameraPaused = false
    if session == nil { startSession() } else { Task { await beginStreamIfReady() } }
  }

  /// Stops the stream and session. Used at the end of a visit.
  func stopStreaming() {
    wantStream = false
    cameraPaused = false
    reconnectTask?.cancel()
    camera?.stop()
    session?.stop()
  }

  /// Privacy pause keeps the session up so resume is instant. The paused flag stops the
  /// stream-stopped handler and reconnect timer from turning the camera back on by themselves.
  func pauseCamera() {
    cameraPaused = true
    reconnectTask?.cancel()
    camera?.stop()
  }

  func resumeCamera() {
    wantStream = true
    cameraPaused = false
    Task { await beginStreamIfReady() }
  }

  private func startSession() {
    guard registration == .registered else {
      link = .notRegistered
      return
    }
    guard session == nil else { return }
    guard let wearables, let selector else {
      link = .error("Glasses are unavailable on this phone.")
      return
    }
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
      if let e = error as? DeviceSessionError, e == .notRegistered {
        // SDK 1.1: createSession throws until Meta AI registration is done. The registration
        // stream starts the session once it reaches .registered.
        link = .notRegistered
        return
      }
      link = .error(Self.message(for: error) ?? error.localizedDescription)
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
    if let msg = Self.message(for: error) { errorMessage = msg }
  }

  private func beginStreamIfReady() async {
    guard wantStream, !cameraPaused, camera == nil, let s = session, s.state == .started, let wearables else { return }
    do {
      if try await wearables.checkPermissionStatus(.camera) != .granted {
        needsCameraPermission = true
        return
      }
    } catch {
      // Usually transient (glasses link not up yet, Meta AI busy). Retry instead of stalling the visit.
      if let msg = Self.message(for: error) { errorMessage = msg }
      scheduleReconnect()
      return
    }
    guard wantStream, !cameraPaused, camera == nil, session === s, s.state == .started else { return }
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
      if let msg = Self.message(for: error) { errorMessage = msg }
      scheduleReconnect()
    }
  }

  /// Opens the Meta AI app to grant camera access, then starts the stream.
  func requestCameraPermission() async {
    needsCameraPermission = false
    guard let wearables else { return }
    do {
      guard try await wearables.requestPermission(.camera) == .granted else {
        errorMessage = "Camera permission was not granted in the Meta AI app."
        return
      }
      await beginStreamIfReady()
    } catch {
      if let msg = Self.message(for: error) { errorMessage = msg }
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
        case .peakPowerLimit: self.errorMessage = "Glasses are at their power limit. Video paused for a moment."
        case .hingesClosed: self.errorMessage = "Glasses are folded or off your face. Put them on to resume video."
        case .permissionDenied: self.needsCameraPermission = true
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
        if self.previewEnabled, Date().timeIntervalSince(self.lastPreviewAt) > 0.8 {
          self.lastPreviewAt = Date()
          let ci = CIImage(cvPixelBuffer: pb)
          if let cg = self.ciContext.createCGImage(ci, from: ci.extent) {
            let image = UIImage(cgImage: cg)
            Task { @MainActor [weak self] in self?.previewImage = image }
          }
        }
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
      if wantStream, !cameraPaused, session != nil { scheduleReconnect() } else if session != nil { link = .connected }
    @unknown default: break
    }
  }

  /// Silent check (no app switch) of the glasses camera permission in Meta AI.
  func cameraPermissionGranted() async -> Bool {
    guard let wearables else { return false }
    return (try? await wearables.checkPermissionStatus(.camera)) == .granted
  }

  private func countFrame() {
    totalFrames += 1
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
      guard let self, !Task.isCancelled, self.wantStream, !self.cameraPaused else { return }
      if self.session == nil { self.startSession() } else { await self.beginStreamIfReady() }
    }
  }

  // MARK: - Error text

  /// Plain-language text for the SDK errors a technician can act on. nil means "not worth showing"
  /// (for example alreadyRegistered).
  static func message(for error: any Error) -> String? {
    if let e = error as? RegistrationError {
      switch e {
      case .alreadyRegistered: return nil
      case .metaAINotInstalled: return "Install the Meta AI app and pair your glasses in it first."
      case .unauthorized:
        return "Meta AI did not allow FieldPartner. In the Meta AI app open Settings > App info, tap the app version 5 times, turn on Developer Mode, then tap Connect again."
      case .configurationInvalid: return "This FieldPartner build is missing its Meta setup (Team ID or app ID). Rebuild it from Xcode with your Team ID."
      case .networkUnavailable: return "No internet connection. Connect and tap Connect again."
      default: return e.description
      }
    }
    if let e = error as? PermissionError {
      switch e {
      case .metaAINotInstalled: return "Install the Meta AI app and pair your glasses in it first."
      case .noDevice, .noDeviceWithConnection: return "Glasses are not connected. Turn them on, open the case, and keep them near the phone."
      case .requestTimeout: return "Meta AI did not answer. Open the Meta AI app, then try again."
      default: return e.description
      }
    }
    if let e = error as? DeviceSessionError {
      switch e {
      case .noEligibleDevice: return "No glasses found. Turn them on and make sure they are paired in the Meta AI app."
      case .notRegistered: return "Glasses are not connected to FieldPartner yet. Tap Connect."
      case .datAppOnTheGlassesUpdateRequired: return "The glasses need an app update. Open the Meta AI app and update the glasses."
      case .insufficientSDKVersion: return "This FieldPartner build is too old for your glasses. Install the latest FieldPartner build."
      case .thermalCritical, .thermalEmergency: return "Glasses are too hot. Let them cool down."
      case .batteryCritical: return "Glasses battery is almost empty. Charge them."
      case .peakPowerShutdown: return "Glasses hit their power limit and stopped. Try again in a moment."
      case .dwaOutOfStuRange: return nil  // non-blocking update suggestion
      default: return e.description
      }
    }
    return error.localizedDescription
  }

  // MARK: - Simulated glasses (debug builds only)

  #if DEBUG
  /// Pairs Meta's simulated glasses so the whole pipeline can be tested without real glasses.
  /// `demoVideo` plays the bundled furnace video as the glasses camera (works in the iOS
  /// Simulator, which has no camera). Otherwise the phone's back camera is the feed.
  func enableSimulatedGlasses(demoVideo: Bool = true) {
    guard wearables != nil else { return }
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
