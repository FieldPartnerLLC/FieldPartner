import XCTest
@testable import FieldPartner

final class ConsentLocationPolicyTests: XCTestCase {
  func testFirstConsentAsksForPermissionInsteadOfGivingUp() {
    XCTAssertEqual(ConsentLocationPolicy.nextStep(auth: .notDetermined, cachedFixAge: nil), .askPermission)
  }

  func testAuthorizedWithoutFixRequestsOne() {
    XCTAssertEqual(ConsentLocationPolicy.nextStep(auth: .authorized, cachedFixAge: nil), .requestFix)
  }

  func testRecentWarmedUpFixIsUsedWithoutWaiting() {
    XCTAssertEqual(ConsentLocationPolicy.nextStep(auth: .authorized, cachedFixAge: 30), .useCached)
    XCTAssertEqual(ConsentLocationPolicy.nextStep(auth: .authorized, cachedFixAge: 600), .useCached)
  }

  func testStaleFixIsReplaced() {
    XCTAssertEqual(ConsentLocationPolicy.nextStep(auth: .authorized, cachedFixAge: 601), .requestFix)
  }

  func testFutureTimestampIsNotTrusted() {
    XCTAssertEqual(ConsentLocationPolicy.nextStep(auth: .authorized, cachedFixAge: -5), .requestFix)
  }

  func testDeniedSkipsEvenWithACachedFix() {
    XCTAssertEqual(ConsentLocationPolicy.nextStep(auth: .denied, cachedFixAge: 10), .skip)
    XCTAssertEqual(ConsentLocationPolicy.nextStep(auth: .denied, cachedFixAge: nil), .skip)
  }

  func testRemainingNeverNegativeAndCapped() {
    let now = Date()
    XCTAssertEqual(ConsentLocationPolicy.remaining(until: now.addingTimeInterval(3), now: now), 3, accuracy: 0.001)
    XCTAssertEqual(ConsentLocationPolicy.remaining(until: now.addingTimeInterval(-2), now: now), 0)
    XCTAssertLessThanOrEqual(ConsentLocationPolicy.maxWait, 5)
  }
}
