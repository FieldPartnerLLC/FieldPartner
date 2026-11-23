import Foundation

/// Location permission as the consent stamp sees it. Kept free of CoreLocation so the
/// decision logic runs in `swift test`.
enum LocationAuth: Equatable {
  case notDetermined
  case denied
  case authorized
}

/// Decides how to stamp the consent record with where the customer signed.
/// The phone warms up a fix when the visit starts; at signing it uses that fix if recent,
/// otherwise asks for permission or a fresh fix, never holding the consent screen longer
/// than `maxWait`. Consent is always saved, with or without a location.
enum ConsentLocationPolicy {
  /// A fix this recent is good enough: the tech is at the customer's door.
  static let maxFixAge: TimeInterval = 10 * 60
  /// The consent screen waits at most this long for permission plus a fix, in total.
  static let maxWait: TimeInterval = 5

  enum Step: Equatable {
    case useCached
    case askPermission
    case requestFix
    case skip
  }

  static func nextStep(auth: LocationAuth, cachedFixAge: TimeInterval?) -> Step {
    if auth == .denied { return .skip }
    if isFresh(age: cachedFixAge) { return .useCached }
    return auth == .notDetermined ? .askPermission : .requestFix
  }

  static func isFresh(age: TimeInterval?) -> Bool {
    guard let age else { return false }
    return age >= 0 && age <= maxFixAge
  }

  /// Seconds left before `deadline`, never negative.
  static func remaining(until deadline: Date, now: Date = Date()) -> TimeInterval {
    max(0, deadline.timeIntervalSince(now))
  }
}
