import Foundation

/// Where call audio goes during a live visit. Pure logic (no AVFoundation) so it is unit tested
/// with `swift test`; LiveSession feeds it the current audio route and applies the plan.
///
/// Glasses mode: the glasses' Bluetooth HFP mic and speakers whenever they are connected, including
/// when they connect after the call has started. When they drop, input goes back to the phone and
/// the AI's voice plays on the loudspeaker (the phone is usually in a pocket, so the earpiece would
/// be inaudible). Phone-camera mode always uses the loudspeaker and leaves input to iOS.
enum AudioRoutePolicy {
  enum Event: Equatable {
    /// The call just connected.
    case callStarted
    /// A Bluetooth or wired device appeared (AVAudioSession reason .newDeviceAvailable).
    case deviceAdded
    /// A device went away (reason .oldDeviceUnavailable).
    case deviceRemoved
    /// Any other route change, including the ones our own setPreferredInput causes.
    case other
  }

  enum Input: Equatable {
    /// Leave the input as it is.
    case keep
    /// setPreferredInput(the glasses' HFP port).
    case preferGlasses
    /// setPreferredInput(nil): let iOS pick (the phone's mic).
    case systemDefault
  }

  struct Plan: Equatable {
    let input: Input
    /// AudioManager.shared.isSpeakerOutputPreferred.
    let speakerPreferred: Bool
  }

  /// Returns nil when the event should be ignored. Reacting to every route change would loop,
  /// because changing the preferred input or the speaker preference posts route changes itself.
  static func plan(
    event: Event,
    phoneCamera: Bool,
    glassesAvailable: Bool,
    glassesInUse: Bool,
    glassesPreferred: Bool
  ) -> Plan? {
    guard event != .other else { return nil }
    if phoneCamera { return Plan(input: .keep, speakerPreferred: true) }
    if glassesAvailable {
      return Plan(input: glassesInUse ? .keep : .preferGlasses, speakerPreferred: false)
    }
    return Plan(input: glassesPreferred ? .systemDefault : .keep, speakerPreferred: true)
  }

  /// Maps AVAudioSession.RouteChangeReason raw values (kept as integers so this file has no
  /// AVFoundation dependency): 1 newDeviceAvailable, 2 oldDeviceUnavailable.
  static func event(forRouteChangeReason raw: UInt) -> Event {
    switch raw {
    case 1: return .deviceAdded
    case 2: return .deviceRemoved
    default: return .other
    }
  }
}
