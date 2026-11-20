import AVFoundation
import MediaPlayer
import UIKit

/// Turns a cheap Bluetooth camera-shutter remote (it sends "volume up") or the
/// phone's own volume buttons into a hands-free trigger:
///   one press  -> talk to the AI (push to talk)
///   two quick  -> stop the AI
///   three quick-> pause / resume the camera
///   hold (3 presses in a row within 1.2 s while held) is not detectable, so
///   "call expert" is on the screen and by voice.
/// The volume is put back after each press so the call volume does not drift.
@MainActor
final class ClipButton: NSObject {
  enum Action { case talk, stop, toggleCamera }

  var onAction: ((Action) -> Void)?
  private var observation: NSKeyValueObservation?
  private let volumeView = MPVolumeView(frame: CGRect(x: -2000, y: -2000, width: 1, height: 1))
  private var baseline: Float = 0.5
  private var resetting = false
  private var presses = 0
  private var pressTimer: Timer?

  func start(in window: UIWindow?) {
    guard observation == nil else { return }
    window?.addSubview(volumeView)
    let session = AVAudioSession.sharedInstance()
    baseline = session.outputVolume
    // Keep room to detect presses at both ends of the range.
    if baseline > 0.9 || baseline < 0.1 { setVolume(0.6) }
    observation = session.observe(\.outputVolume, options: [.new]) { [weak self] _, change in
      guard let v = change.newValue else { return }
      Task { @MainActor in self?.volumeChanged(to: v) }
    }
  }

  func stop() {
    observation?.invalidate()
    observation = nil
    volumeView.removeFromSuperview()
  }

  private func volumeChanged(to value: Float) {
    if resetting {
      resetting = false
      return
    }
    presses += 1
    pressTimer?.invalidate()
    pressTimer = Timer.scheduledTimer(withTimeInterval: 0.45, repeats: false) { [weak self] _ in
      Task { @MainActor in self?.firePresses() }
    }
    setVolume(baseline)
  }

  private func firePresses() {
    let n = presses
    presses = 0
    switch n {
    case 1: onAction?(.talk)
    case 2: onAction?(.stop)
    default: onAction?(.toggleCamera)
    }
  }

  private func setVolume(_ value: Float) {
    guard let slider = volumeView.subviews.compactMap({ $0 as? UISlider }).first else { return }
    resetting = true
    baseline = value
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { slider.value = value }
  }
}
