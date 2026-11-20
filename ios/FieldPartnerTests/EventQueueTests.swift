import XCTest
@testable import FieldPartner

/// The event queue is what keeps a visit record intact through dead zones and crashes.
final class EventQueueTests: XCTestCase {
  var dir: URL!

  override func setUp() {
    dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
  }

  func testKeepsEventsWhileOfflineAndDeliversWhenBack() async {
    let q = EventQueue(visitId: "v1", directory: dir)
    for i in 0..<5 { await q.add(VisitEvent(type: "note", data: ["i": .number(Double(i))])) }
    var delivered = await q.flush { _ in throw APIError.offline }
    XCTAssertEqual(delivered, 0)
    let count1 = await q.count
    XCTAssertEqual(count1, 5)
    var sent: [VisitEvent] = []
    delivered = await q.flush { batch in sent += batch }
    XCTAssertEqual(delivered, 5)
    XCTAssertEqual(sent.count, 5)
    let count2 = await q.count
    XCTAssertEqual(count2, 0)
  }

  func testSurvivesAppRestart() async {
    let q1 = EventQueue(visitId: "v2", directory: dir)
    await q1.add(VisitEvent(type: "reading", data: ["name": .string("CO"), "value": .number(0)]))
    await q1.add(VisitEvent(type: "note", data: ["text": .string("before crash")]))
    let q2 = EventQueue(visitId: "v2", directory: dir)  // app relaunched
    let count = await q2.count
    XCTAssertEqual(count, 2)
  }

  func testRejectedBatchIsDroppedNotRetriedForever() async {
    let q = EventQueue(visitId: "v3", directory: dir)
    await q.add(VisitEvent(type: "note"))
    await q.flush { _ in throw APIError.server(status: 400, code: "bad_event", message: "bad") }
    let count = await q.count
    XCTAssertEqual(count, 0)
    let err = await q.lastError
    XCTAssertEqual(err, "bad")
  }

  func testServerErrorsKeepEventsForRetry() async {
    let q = EventQueue(visitId: "v4", directory: dir)
    await q.add(VisitEvent(type: "note"))
    await q.flush { _ in throw APIError.server(status: 503, code: "down", message: "down") }
    let count = await q.count
    XCTAssertEqual(count, 1)
  }

  func testLargeBacklogGoesInBatches() async {
    let q = EventQueue(visitId: "v5", directory: dir)
    for _ in 0..<450 { await q.add(VisitEvent(type: "transcript", data: ["text": .string("x")])) }
    var batches: [Int] = []
    await q.flush { batch in batches.append(batch.count) }
    XCTAssertEqual(batches, [200, 200, 50])
  }

  func testEventIdsAreUniqueForServerDedup() {
    let ids = Set((0..<1000).map { _ in VisitEvent(type: "note").id })
    XCTAssertEqual(ids.count, 1000)
  }

  func testJSONValueRoundTrip() throws {
    let e = VisitEvent(type: "equipment", data: ["brand": .string("Carrier"), "year": .number(2018), "ok": .bool(true), "tags": .array([.string("gas")])])
    let back = try JSONDecoder().decode(VisitEvent.self, from: JSONEncoder().encode(e))
    XCTAssertEqual(back, e)
    XCTAssertEqual(JSONValue.number(2018).stringValue, "2018")
  }

  func testISODates() {
    XCTAssertNotNil(ISO8601.parse("2026-11-20T15:04:05.123Z"))
    XCTAssertNotNil(ISO8601.parse("2026-11-20T15:04:05Z"))
    XCTAssertNil(ISO8601.parse("yesterday"))
  }
}
