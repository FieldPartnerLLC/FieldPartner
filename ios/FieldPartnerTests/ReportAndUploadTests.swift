import XCTest
@testable import FieldPartner

/// Report parsing, report cleanup before approval, and upload URL rewriting. These mirror the
/// Android app's ServiceReport defaults, ServiceReport.cleaned() and UploadQueue.rewriteLocalhost.
final class ReportAndUploadTests: XCTestCase {
  func testReportWithMissingSectionsStillDecodes() throws {
    let json = #"{"status":"draft","report":{"summary":"Replaced igniter","findings":["Cracked igniter"]},"token":null}"#
    let r = try JSONDecoder().decode(ReportResponse.self, from: Data(json.utf8))
    XCTAssertEqual(r.report?.summary, "Replaced igniter")
    XCTAssertEqual(r.report?.findings, ["Cracked igniter"])
    XCTAssertEqual(r.report?.readings, [])
    XCTAssertEqual(r.report?.equipment, "")
  }

  func testFullReportRoundTrips() throws {
    let report = ServiceReport(
      customerComplaint: "No heat", equipment: "Carrier 59SC", safetyReadings: ["CO 0 ppm"], readings: ["Gas 3.5 inWC"],
      findings: ["Dirty flame sensor"], workPerformed: ["Cleaned sensor"], partsUsed: [], observations: [],
      safetyEvents: [], supervisorInvolvement: "", customerDecisions: [], followUp: ["Replace filter"], summary: "Heat restored")
    let data = try JSONEncoder().encode(report)
    XCTAssertEqual(try JSONDecoder().decode(ServiceReport.self, from: data), report)
  }

  func testCleanedTrimsAndDropsBlankLines() {
    var report = try! JSONDecoder().decode(ServiceReport.self, from: Data("{}".utf8))
    report.summary = "  Heat restored \n"
    report.findings = ["Dirty sensor", "", "  ", " Loose wire "]
    let c = report.cleaned()
    XCTAssertEqual(c.summary, "Heat restored")
    XCTAssertEqual(c.findings, ["Dirty sensor", "Loose wire"])
  }

  func testLocalhostUploadURLPointsAtTheAppServer() {
    let signed = URL(string: "http://localhost:8080/media/local/c/v/1-a.mov?exp=1&sig=abc")!
    let base = URL(string: "http://192.168.1.20:8080")!
    let out = APIClient.rewriteLocalhost(signed, apiBase: base)
    XCTAssertEqual(out.absoluteString, "http://192.168.1.20:8080/media/local/c/v/1-a.mov?exp=1&sig=abc")
  }

  func testRealUploadURLIsUntouched() {
    let signed = URL(string: "https://fieldpartner-api.onrender.com/media/local/x.mov?exp=1&sig=abc")!
    let base = URL(string: "https://fieldpartner-api.onrender.com")!
    XCTAssertEqual(APIClient.rewriteLocalhost(signed, apiBase: base), signed)
    let s3 = URL(string: "https://bucket.s3.amazonaws.com/x.mov?X-Amz-Signature=1")!
    XCTAssertEqual(APIClient.rewriteLocalhost(s3, apiBase: base), s3)
  }
}
