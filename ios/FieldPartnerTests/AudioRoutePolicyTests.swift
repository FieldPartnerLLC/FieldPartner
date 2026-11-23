import XCTest
@testable import FieldPartner

final class AudioRoutePolicyTests: XCTestCase {
  private func plan(
    _ event: AudioRoutePolicy.Event, phoneCamera: Bool = false,
    available: Bool, inUse: Bool = false, preferred: Bool = false
  ) -> AudioRoutePolicy.Plan? {
    AudioRoutePolicy.plan(
      event: event, phoneCamera: phoneCamera,
      glassesAvailable: available, glassesInUse: inUse, glassesPreferred: preferred)
  }

  func testGlassesConnectingMidCallTakeTheMicAndSpeakers() {
    XCTAssertEqual(plan(.deviceAdded, available: true), .init(input: .preferGlasses, speakerPreferred: false))
  }

  func testGlassesAlreadyInUseAreLeftAlone() {
    XCTAssertEqual(plan(.callStarted, available: true, inUse: true, preferred: true), .init(input: .keep, speakerPreferred: false))
  }

  func testCallStartingWithoutGlassesUsesTheLoudspeaker() {
    XCTAssertEqual(plan(.callStarted, available: false), .init(input: .keep, speakerPreferred: true))
  }

  func testGlassesDroppingFallsBackToPhoneMicAndLoudspeaker() {
    XCTAssertEqual(plan(.deviceRemoved, available: false, preferred: true), .init(input: .systemDefault, speakerPreferred: true))
  }

  func testPhoneCameraModeAlwaysUsesTheLoudspeakerAndNeverForcesGlasses() {
    XCTAssertEqual(plan(.deviceAdded, phoneCamera: true, available: true), .init(input: .keep, speakerPreferred: true))
    XCTAssertEqual(plan(.callStarted, phoneCamera: true, available: false), .init(input: .keep, speakerPreferred: true))
  }

  func testOtherRouteChangesAreIgnoredSoOurOwnChangesDoNotLoop() {
    XCTAssertNil(plan(.other, available: true))
    XCTAssertNil(plan(.other, available: false, preferred: true))
  }

  func testRouteChangeReasonMapping() {
    XCTAssertEqual(AudioRoutePolicy.event(forRouteChangeReason: 1), .deviceAdded)
    XCTAssertEqual(AudioRoutePolicy.event(forRouteChangeReason: 2), .deviceRemoved)
    XCTAssertEqual(AudioRoutePolicy.event(forRouteChangeReason: 3), .other)  // category change
    XCTAssertEqual(AudioRoutePolicy.event(forRouteChangeReason: 4), .other)  // override
    XCTAssertEqual(AudioRoutePolicy.event(forRouteChangeReason: 8), .other)  // route configuration change
  }
}
