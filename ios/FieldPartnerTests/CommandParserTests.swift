import XCTest
@testable import FieldPartner

/// Real phrases from the field, including recognizer mistakes and background talk.
final class CommandParserTests: XCTestCase {
  let p = CommandParser(wakePhrase: "hey dispatch")

  func testQuestionAfterWakePhrase() {
    XCTAssertEqual(p.parse(finalUtterance: "Hey Dispatch, what does three flashes mean on this board?"),
                   .ask("what does three flashes mean on this board"))
  }

  func testRecognizerVariantsOfTheWakePhrase() {
    XCTAssertEqual(p.parse(finalUtterance: "Hi dispatch read the plate"), .ask("read the plate"))
    XCTAssertEqual(p.parse(finalUtterance: "okay dispatch read the plate"), .ask("read the plate"))
    XCTAssertEqual(p.parse(finalUtterance: "hey dispatcher read the plate"), .ask("read the plate"))
  }

  func testWakePhraseMidSentence() {
    XCTAssertEqual(p.parse(finalUtterance: "alright so hey dispatch is this capacitor bad"), .ask("is this capacitor bad"))
  }

  func testWakeAloneOpensTheMic() {
    XCTAssertEqual(p.parse(finalUtterance: "Hey dispatch."), .ask(""))
  }

  func testCustomerConversationIsIgnored() {
    XCTAssertNil(p.parse(finalUtterance: "It started making that noise yesterday, can you stop it?"))
    XCTAssertNil(p.parse(finalUtterance: "Stop."))  // a customer saying stop does nothing
    XCTAssertNil(p.parse(finalUtterance: "We called dispatch twice already"))
  }

  func testShortCommandsAfterWake() {
    XCTAssertEqual(p.parse(finalUtterance: "hey dispatch stop"), .stop)
    XCTAssertEqual(p.parse(finalUtterance: "hey dispatch pause camera"), .pauseCamera)
    XCTAssertEqual(p.parse(finalUtterance: "hey dispatch camera on"), .resumeCamera)
    XCTAssertEqual(p.parse(finalUtterance: "hey dispatch go ahead"), .playHint)
    XCTAssertEqual(p.parse(finalUtterance: "hey dispatch call Mitch"), .callExpert)
    XCTAssertEqual(p.parse(finalUtterance: "hey dispatch stop recording"), .stopRecording)
  }

  func testShortCommandsByName() {
    XCTAssertEqual(p.parse(finalUtterance: "Partner, stop."), .stop)
    XCTAssertEqual(p.parse(finalUtterance: "partner continue"), .continueLast)
    XCTAssertEqual(p.parse(finalUtterance: "partner acknowledged"), .acknowledge)
    XCTAssertNil(p.parse(finalUtterance: "my partner will be here tomorrow"))
  }

  func testDisagreement() {
    if case .disputed = p.parse(finalUtterance: "hey dispatch that's wrong, it's a heat pump") {} else {
      XCTFail("expected disputed")
    }
  }

  func testPartialWakeDetectionForEarlyChime() {
    XCTAssertTrue(p.wakeHeard(inPartial: "hey dispatch what"))
    XCTAssertFalse(p.wakeHeard(inPartial: "hey dis"))
  }

  func testCustomWakePhrasePerCompany() {
    let roofing = CommandParser(wakePhrase: "Tech Assist")
    XCTAssertEqual(roofing.parse(finalUtterance: "tech assist, is this flashing lifted?"), .ask("is this flashing lifted"))
    XCTAssertNil(roofing.parse(finalUtterance: "hey dispatch is this flashing lifted"))
  }

  func testWordBoundary() {
    let short = CommandParser(wakePhrase: "hey max")
    XCTAssertNil(short.parse(finalUtterance: "they maximum load is fine"))
  }

  func testTalkAboutADispatchDoesNotWake() {
    XCTAssertNil(p.parse(finalUtterance: "We sent a dispatch out to the site yesterday"))
    XCTAssertNil(p.parse(finalUtterance: "I said okay, dispatch will call you back"))
    XCTAssertNil(p.parse(finalUtterance: "Hey, dispatching the truck now"))
  }

  func testEverydayWordVariantsWorkAtTheStart() {
    XCTAssertEqual(p.parse(finalUtterance: "Okay dispatch, read the plate"), .ask("read the plate"))
    XCTAssertEqual(p.parse(finalUtterance: "A dispatch what is this code"), .ask("what is this code"))
  }

  func testWakePhraseNeedsAWordBoundaryAfterIt() {
    let short = CommandParser(wakePhrase: "hey max")
    XCTAssertNil(short.parse(finalUtterance: "hey maxwell is here"))
  }

  func testLaterOccurrenceIsFound() {
    let short = CommandParser(wakePhrase: "hey max")
    XCTAssertEqual(short.parse(finalUtterance: "they max out, hey max is this ok"), .ask("is this ok"))
  }
}
