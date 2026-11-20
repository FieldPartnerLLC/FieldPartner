import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

enum APIError: LocalizedError, Equatable {
  case notSignedIn
  case server(status: Int, code: String, message: String)
  case offline
  case timeout
  case badResponse

  var errorDescription: String? {
    switch self {
    case .notSignedIn: return "Sign in again."
    case .server(_, _, let message): return message
    case .offline: return "No connection to the server. Check signal or Wi-Fi."
    case .timeout: return "The server took too long to answer. Try again."
    case .badResponse: return "Unexpected reply from the server."
    }
  }

  /// Worth retrying automatically (network trouble or a server hiccup), as opposed to a real "no".
  var isTransient: Bool {
    switch self {
    case .offline, .timeout: return true
    case .server(let status, _, _): return status >= 500 || status == 429
    default: return false
    }
  }
}

/// Thin async client for the FieldPartner backend. Every call has a timeout and
/// transient failures retry with backoff, so a weak cell signal degrades into a
/// short delay instead of an error on the technician's screen.
final class APIClient: @unchecked Sendable {
  var baseURL: URL
  var token: String?
  private let session: URLSession
  private let decoder = JSONDecoder()
  private let encoder = JSONEncoder()

  init(baseURL: URL, token: String?) {
    self.baseURL = baseURL
    self.token = token
    let cfg = URLSessionConfiguration.default
    cfg.timeoutIntervalForRequest = 20
    cfg.timeoutIntervalForResource = 60
    #if os(iOS)
    cfg.waitsForConnectivity = false
    #endif
    self.session = URLSession(configuration: cfg)
  }

  struct Empty: Codable {}

  func request<T: Decodable>(
    _ method: String,
    _ path: String,
    body: Encodable? = nil,
    query: [String: String] = [:],
    retries: Int = 2,
    authenticated: Bool = true
  ) async throws -> T {
    var attempt = 0
    var delay: UInt64 = 600_000_000
    while true {
      do {
        return try await send(method, path, body: body, query: query, authenticated: authenticated)
      } catch let e as APIError where e.isTransient && attempt < retries {
        attempt += 1
        try await Task.sleep(nanoseconds: delay)
        delay *= 2
      }
    }
  }

  private func send<T: Decodable>(_ method: String, _ path: String, body: Encodable?, query: [String: String], authenticated: Bool) async throws -> T {
    var comps = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
    if !query.isEmpty { comps.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) } }
    var req = URLRequest(url: comps.url!)
    req.httpMethod = method
    req.setValue("application/json", forHTTPHeaderField: "Accept")
    if authenticated {
      guard let token else { throw APIError.notSignedIn }
      req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
    }
    if let body {
      req.setValue("application/json", forHTTPHeaderField: "Content-Type")
      req.httpBody = try encoder.encode(AnyEncodable(body))
    }
    let data: Data
    let response: URLResponse
    do {
      (data, response) = try await session.data(for: req)
    } catch let e as URLError {
      switch e.code {
      case .timedOut: throw APIError.timeout
      default: throw APIError.offline
      }
    }
    guard let http = response as? HTTPURLResponse else { throw APIError.badResponse }
    if http.statusCode >= 400 {
      let err = try? decoder.decode(APIErrorBody.self, from: data)
      if http.statusCode == 401 && authenticated { throw APIError.notSignedIn }
      throw APIError.server(status: http.statusCode, code: err?.error ?? "error", message: err?.message ?? "Server error \(http.statusCode).")
    }
    if T.self == Empty.self { return Empty() as! T }
    do {
      return try decoder.decode(T.self, from: data)
    } catch {
      NSLog("[API] decode %@ failed: %@", path, String(describing: error))
      throw APIError.badResponse
    }
  }

  /// Upload a file to a signed URL (S3 or the backend's local storage).
  func upload(file: URL, to url: URL, contentType: String) async throws {
    var req = URLRequest(url: url)
    req.httpMethod = "PUT"
    req.setValue(contentType, forHTTPHeaderField: "Content-Type")
    req.timeoutInterval = 600
    let (_, response) = try await session.upload(for: req, fromFile: file)
    guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
      throw APIError.server(status: (response as? HTTPURLResponse)?.statusCode ?? 0, code: "upload", message: "Upload failed.")
    }
  }

  // MARK: - Endpoints

  func login(email: String, password: String) async throws -> LoginResponse {
    struct Body: Encodable { let email: String; let password: String }
    return try await request("POST", "/auth/login", body: Body(email: email, password: password), authenticated: false)
  }

  /// Who an invite code is for. Accepts the code with or without the dash, any case.
  func inviteInfo(code: String) async throws -> InviteInfo {
    let clean = code.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: " ", with: "")
    return try await request("GET", "/auth/invite/\(clean)", retries: 1, authenticated: false)
  }

  /// Creates the technician's account from the invite and signs them in.
  func acceptInvite(code: String, password: String) async throws -> LoginResponse {
    struct Body: Encodable { let code: String; let password: String }
    return try await request("POST", "/auth/accept-invite", body: Body(code: code.trimmingCharacters(in: .whitespaces), password: password), retries: 0, authenticated: false)
  }

  func me() async throws -> MeResponse { try await request("GET", "/me") }

  func jobs(date: Date) async throws -> [Job] {
    let f = DateFormatter()
    f.dateFormat = "yyyy-MM-dd"
    let r: JobsResponse = try await request("GET", "/jobs", query: ["date": f.string(from: date), "tz": TimeZone.current.identifier])
    return r.jobs
  }

  func createJob(trade: String, title: String, complaint: String, address: String, customerName: String, customerPhone: String) async throws -> Job {
    struct Customer: Encodable { let name: String; let phone: String? }
    struct Body: Encodable { let trade: String; let title: String; let complaint: String; let address: String; let customer: Customer? }
    let body = Body(
      trade: trade, title: title, complaint: complaint, address: address,
      customer: customerName.isEmpty ? nil : Customer(name: customerName, phone: customerPhone.isEmpty ? nil : customerPhone))
    let r: JobResponse = try await request("POST", "/jobs", body: body)
    return r.job
  }

  func startVisit(jobId: String) async throws -> StartVisitResponse {
    try await request("POST", "/jobs/\(jobId)/visits", retries: 4)
  }

  func liveTicket(visitId: String) async throws -> LiveTicket {
    try await request("POST", "/visits/\(visitId)/live", retries: 3)
  }

  struct ConsentBody: Encodable {
    let kind: String
    let choice: String?
    let privateAreasOk: Bool?
    let signerName: String?
    let signerRole: String?
    let language: String
    let lat: Double?
    let lng: Double?
    let textVersion: String
    let signaturePngBase64: String?
  }

  func consent(visitId: String, body: ConsentBody) async throws -> ConsentResponse {
    try await request("POST", "/visits/\(visitId)/consent", body: body, retries: 4)
  }

  func postEvents(visitId: String, events: [VisitEvent]) async throws {
    struct Body: Encodable { let events: [VisitEvent] }
    struct Res: Decodable { let stored: Int; let duplicates: Int }
    let _: Res = try await request("POST", "/visits/\(visitId)/events", body: Body(events: events), retries: 0)
  }

  func escalate(visitId: String, reason: String) async throws -> EscalationResponse {
    struct Body: Encodable { let reason: String }
    return try await request("POST", "/visits/\(visitId)/escalate", body: Body(reason: reason), retries: 3)
  }

  func endEscalation(visitId: String) async throws {
    let _: Empty = try await request("POST", "/visits/\(visitId)/escalation/end")
  }

  func requestUpload(visitId: String, kind: String, filename: String, contentType: String, bytes: Int, clientId: String) async throws -> UploadTicket {
    struct Body: Encodable { let kind: String; let filename: String; let contentType: String; let bytes: Int; let clientId: String }
    return try await request("POST", "/visits/\(visitId)/uploads", body: Body(kind: kind, filename: filename, contentType: contentType, bytes: bytes, clientId: clientId))
  }

  func completeUpload(mediaId: String, bytes: Int) async throws {
    struct Body: Encodable { let bytes: Int }
    let _: Empty = try await request("POST", "/media/\(mediaId)/complete", body: Body(bytes: bytes))
  }

  func endVisit(visitId: String) async throws -> Visit {
    struct Res: Decodable { let visit: Visit }
    let r: Res = try await request("POST", "/visits/\(visitId)/end", retries: 4)
    return r.visit
  }

  func report(visitId: String) async throws -> ReportResponse {
    try await request("GET", "/visits/\(visitId)/report")
  }

  func approveReport(visitId: String, report: ServiceReport, sendSms: Bool, sendEmail: Bool) async throws -> ApproveResponse {
    struct Body: Encodable { let report: ServiceReport; let sendSms: Bool; let sendEmail: Bool }
    return try await request("POST", "/visits/\(visitId)/report/approve", body: Body(report: report, sendSms: sendSms, sendEmail: sendEmail))
  }
}

/// Type-erases Encodable so request() can take `body: Encodable?`.
struct AnyEncodable: Encodable {
  let value: Encodable
  init(_ value: Encodable) { self.value = value }
  func encode(to encoder: Encoder) throws { try value.encode(to: encoder) }
}
