import XCTest

/// Runs on the simulator with no server: checks the first two screens a technician sees
/// and saves a screenshot of each (exported to ci-out/ui-screenshots in CI).
final class LoginFlowUITests: XCTestCase {
  override func setUpWithError() throws {
    continueAfterFailure = false
  }

  func testLoginAndInviteScreens() throws {
    let app = XCUIApplication()
    app.launch()

    // Login screen
    XCTAssertTrue(app.staticTexts["FieldPartner"].waitForExistence(timeout: 30), "FieldPartner title missing")
    let server = app.textFields.matching(NSPredicate(format: "value CONTAINS[c] %@", "fieldpartner-api.onrender.com")).firstMatch
    XCTAssertTrue(server.waitForExistence(timeout: 10), "Company server field is not prefilled with the live server")
    let signIn = app.buttons.matching(NSPredicate(format: "label == %@", "Sign in")).firstMatch
    XCTAssertTrue(signIn.exists, "Sign in button missing")
    let invite = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "invite code")).firstMatch
    XCTAssertTrue(invite.waitForExistence(timeout: 5), "'I have an invite code' button missing")
    attach(app, "01-login")

    // Invite screen
    if !invite.isHittable { app.swipeUp() }
    invite.tap()
    XCTAssertTrue(app.navigationBars["Join your company"].waitForExistence(timeout: 15), "Join screen did not open")
    let code = app.textFields.matching(NSPredicate(format: "placeholderValue == %@", "ABCD-2345")).firstMatch
    XCTAssertTrue(code.waitForExistence(timeout: 5), "Invite code field missing")
    attach(app, "02-invite")
  }

  private func attach(_ app: XCUIApplication, _ name: String) {
    let shot = XCTAttachment(screenshot: app.screenshot())
    shot.name = name
    shot.lifetime = .keepAlways
    add(shot)
  }
}
