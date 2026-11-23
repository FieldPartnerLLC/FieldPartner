import Foundation

/// Encoding for the glasses video sent to the live room. The AI samples 1 frame a second, so with
/// only the AI watching the phone sends 5 fps at 1 Mbps (about 0.45 GB per visit hour on cellular).
/// A human expert gets smooth video for as long as they are in the call.
struct LiveVideoProfile: Equatable {
  let maxBitrate: Int
  let maxFps: Int

  static let aiOnly = LiveVideoProfile(maxBitrate: 1_000_000, maxFps: 5)
  static let expert = LiveVideoProfile(maxBitrate: 2_500_000, maxFps: 15)

  static func forRoom(expertPresent: Bool) -> LiveVideoProfile {
    expertPresent ? .expert : .aiOnly
  }
}
