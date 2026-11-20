import Foundation

// Wire models for the FieldPartner backend. Field names follow the server's JSON
// (snake_case database columns, camelCase API objects).

struct UserInfo: Codable, Equatable {
  let id: String
  let name: String
  let email: String
  let role: String
  let trades: [String]?
  let certifications: [String]?
  let companyId: String?
}

struct CompanyInfo: Codable, Equatable {
  let id: String
  let name: String
  let phone: String?
  let wake_phrase: String
  let co_stop_ppm: Int
  let retention_days: Int?
  let diagnostic_fee_cents: Int?
  let license_numbers: String?
  let address: String?
}

struct InviteInfo: Codable, Equatable {
  let companyName: String
  let name: String
  let email: String
  let role: String
}

struct LoginResponse: Codable {
  let token: String
  let user: UserInfo
  let company: CompanyInfo?
}

struct MeResponse: Codable {
  let user: UserInfo
  let company: CompanyInfo?
  let trades: [TradeProfileRow]
}

struct TradeProfileRow: Codable, Identifiable, Equatable {
  let id: String
  let name: String
  let profile: TradeProfile
}

struct TradeProfile: Codable, Equatable {
  struct PreWork: Codable, Equatable, Hashable {
    let key: String
    let label: String
    let unit: String?
    let requiredWhen: String?
  }
  let id: String?
  let name: String?
  let description: String?
  let preWorkReadings: [PreWork]?
  let stopRules: [String]?
  let neverGuide: [String]?
  let verification: [String]?
}

struct LastVisit: Codable, Equatable {
  let id: String
  let state: String
  let ended_at: String?
  let report_status: String?
}

struct Job: Codable, Identifiable, Equatable {
  let id: String
  let company_id: String
  let trade: String
  let title: String
  let complaint: String?
  let address: String?
  let scheduled_at: String?
  let status: String
  let assigned_tech_id: String?
  let access_notes: String?
  let recording_pref: String?
  let customer_name: String?
  let customer_phone: String?
  let customer_email: String?
  let tech_name: String?
  let last_visit: LastVisit?

  var scheduledDate: Date? { scheduled_at.flatMap { ISO8601.parse($0) } }
}

struct JobsResponse: Codable { let jobs: [Job] }
struct JobResponse: Codable { let job: Job }

struct Visit: Codable, Identifiable, Equatable {
  let id: String
  let job_id: String
  let company_id: String
  let tech_id: String
  let room_name: String
  let state: String
  let consent_choice: String?
  let private_areas_ok: Bool?
  let started_at: String
  let ended_at: String?
  let escalation_status: String?
  let report_status: String?
}

struct StartVisitResponse: Codable {
  struct Live: Codable { let available: Bool }
  let visit: Visit
  let resumed: Bool
  let live: Live
}

struct LiveTicket: Codable {
  let url: String
  let room: String
  let token: String
  let identity: String
}

struct ConsentResponse: Codable {
  let consentId: String
  let recording: String
  let cloudRecording: Bool
}

struct EscalationResponse: Codable {
  let status: String
  let paged: String?
}

struct UploadTicket: Codable {
  let mediaId: String
  let url: String?
  let key: String?
  let alreadyUploaded: Bool?
}

struct ServiceReport: Codable, Equatable {
  var customerComplaint: String
  var equipment: String
  var safetyReadings: [String]
  var readings: [String]
  var findings: [String]
  var workPerformed: [String]
  var partsUsed: [String]
  var observations: [String]
  var safetyEvents: [String]
  var supervisorInvolvement: String
  var customerDecisions: [String]
  var followUp: [String]
  var summary: String
}

struct ReportResponse: Codable {
  let status: String
  let report: ServiceReport?
  let token: String?
}

struct ApproveResponse: Codable {
  let token: String
  let url: String
  let delivered: [String]
}

struct APIErrorBody: Codable {
  let error: String?
  let message: String?
}

/// A visit event, created on the phone and delivered to the server at least once.
struct VisitEvent: Codable, Identifiable, Equatable {
  let id: String
  let ts: String
  let type: String
  let actor: String
  let data: [String: JSONValue]

  init(type: String, actor: String = "tech", data: [String: JSONValue] = [:], date: Date = Date()) {
    self.id = "ios-\(UUID().uuidString)"
    self.ts = ISO8601.string(date)
    self.type = type
    self.actor = actor
    self.data = data
  }
}

/// Minimal JSON value so events can carry free-form data.
enum JSONValue: Codable, Equatable {
  case string(String)
  case number(Double)
  case bool(Bool)
  case object([String: JSONValue])
  case array([JSONValue])
  case null

  init(from decoder: Decoder) throws {
    let c = try decoder.singleValueContainer()
    if c.decodeNil() { self = .null }
    else if let b = try? c.decode(Bool.self) { self = .bool(b) }
    else if let n = try? c.decode(Double.self) { self = .number(n) }
    else if let s = try? c.decode(String.self) { self = .string(s) }
    else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
    else { self = .object(try c.decode([String: JSONValue].self)) }
  }

  func encode(to encoder: Encoder) throws {
    var c = encoder.singleValueContainer()
    switch self {
    case .string(let s): try c.encode(s)
    case .number(let n): try c.encode(n)
    case .bool(let b): try c.encode(b)
    case .object(let o): try c.encode(o)
    case .array(let a): try c.encode(a)
    case .null: try c.encodeNil()
    }
  }

  var stringValue: String? {
    switch self {
    case .string(let s): return s
    case .number(let n): return n == n.rounded() ? String(Int(n)) : String(n)
    case .bool(let b): return b ? "true" : "false"
    default: return nil
    }
  }

  static func from(_ any: Any?) -> JSONValue {
    switch any {
    case nil: return .null
    case let s as String: return .string(s)
    case let b as Bool: return .bool(b)
    case let i as Int: return .number(Double(i))
    case let d as Double: return .number(d)
    case let a as [Any]: return .array(a.map { from($0) })
    case let o as [String: Any]: return .object(o.mapValues { from($0) })
    default: return .string(String(describing: any!))
    }
  }
}

enum ISO8601 {
  private static let withFraction: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f
  }()
  private static let plain: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime]
    return f
  }()

  static func string(_ d: Date) -> String { withFraction.string(from: d) }
  static func parse(_ s: String) -> Date? { withFraction.date(from: s) ?? plain.date(from: s) }
}
