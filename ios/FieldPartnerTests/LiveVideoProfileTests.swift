import XCTest
@testable import FieldPartner

final class LiveVideoProfileTests: XCTestCase {
  func testAIOnlySavesDataAndStillOutpacesTheAISampler() {
    XCTAssertEqual(LiveVideoProfile.forRoom(expertPresent: false), .aiOnly)
    XCTAssertEqual(LiveVideoProfile.aiOnly.maxFps, 5)
    XCTAssertEqual(LiveVideoProfile.aiOnly.maxBitrate, 1_000_000)
    XCTAssertGreaterThanOrEqual(LiveVideoProfile.aiOnly.maxFps, 1)  // agent samples 1 frame a second
  }

  func testExpertGetsSmoothVideo() {
    XCTAssertEqual(LiveVideoProfile.forRoom(expertPresent: true), .expert)
    XCTAssertEqual(LiveVideoProfile.expert.maxFps, 15)
    XCTAssertEqual(LiveVideoProfile.expert.maxBitrate, 2_500_000)
  }
}
