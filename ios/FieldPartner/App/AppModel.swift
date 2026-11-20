import Foundation
import SwiftUI

@MainActor
final class AppModel: ObservableObject {
  @Published private(set) var user: UserInfo?
  @Published private(set) var company: CompanyInfo?
  @Published private(set) var trades: [TradeProfileRow] = []
  @Published private(set) var jobs: [Job] = []
  @Published private(set) var loadingJobs = false
  @Published var visit: VisitController?
  @Published var reportVisitId: String?
  @Published var errorMessage: String?

  let glasses = GlassesManager()
  private(set) var api: APIClient

  init() {
    let settings = AppSettings.shared
    let url = URL(string: settings.apiBaseURL) ?? URL(string: "http://localhost:8080")!
    api = APIClient(baseURL: url, token: Keychain.get("token"))
    Task { await UploadManager.shared.configure(api: api) }
    if api.token != nil { Task { await refreshMe() } }
  }

  var signedIn: Bool { user != nil }

  func setServer(_ urlString: String) {
    let trimmed = urlString.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    guard let url = URL(string: trimmed), url.scheme != nil else { return }
    AppSettings.shared.apiBaseURL = trimmed
    api.baseURL = url
  }

  func login(server: String, email: String, password: String) async {
    setServer(server)
    do {
      let r = try await api.login(email: email.trimmingCharacters(in: .whitespaces), password: password)
      Keychain.set(r.token, for: "token")
      api.token = r.token
      user = r.user
      company = r.company
      await UploadManager.shared.configure(api: api)
      await refreshMe()
      await loadJobs()
    } catch {
      errorMessage = error.localizedDescription
    }
  }

  func logout() {
    Keychain.set(nil, for: "token")
    api.token = nil
    user = nil
    company = nil
    jobs = []
  }

  func refreshMe() async {
    do {
      let me = try await api.me()
      user = me.user
      company = me.company
      trades = me.trades
      await loadJobs()
    } catch APIError.notSignedIn {
      logout()
    } catch {
      errorMessage = error.localizedDescription
    }
  }

  func loadJobs() async {
    loadingJobs = true
    defer { loadingJobs = false }
    do {
      jobs = try await api.jobs(date: Date())
    } catch APIError.notSignedIn {
      logout()
    } catch {
      errorMessage = error.localizedDescription
    }
  }

  func trade(_ id: String) -> TradeProfileRow? { trades.first { $0.id == id } }

  func createWalkIn(trade: String, title: String, complaint: String, address: String, customer: String, phone: String) async -> Job? {
    do {
      let job = try await api.createJob(trade: trade, title: title, complaint: complaint, address: address, customerName: customer, customerPhone: phone)
      await loadJobs()
      return job
    } catch {
      errorMessage = error.localizedDescription
      return nil
    }
  }

  func openVisit(for job: Job) async -> VisitController? {
    do {
      let r = try await api.startVisit(jobId: job.id)
      let controller = VisitController(visit: r.visit, job: job, api: api, company: company, glasses: glasses)
      visit = controller
      return controller
    } catch {
      errorMessage = error.localizedDescription
      return nil
    }
  }

  func closeVisit() {
    if let v = visit, v.phase == .ended { reportVisitId = v.visit.id }
    visit = nil
    Task { await loadJobs() }
  }
}
