import Foundation
import Network

/// Uploads recordings after (or during) visits. Survives app restarts, waits for
/// Wi-Fi for video unless the user allows cellular, and retries forever with backoff.
/// A file is deleted from the phone only after the server confirms it.
actor UploadManager {
  static let shared = UploadManager()

  struct Item: Codable, Equatable {
    let visitId: String
    let path: String
    let kind: String
    let contentType: String
    let clientId: String
    var attempts: Int
  }

  private var items: [Item] = []
  private var api: APIClient?
  private var running = false
  private let monitor = NWPathMonitor()
  private var onWiFi = false
  private var online = false
  private let stateURL: URL

  init() {
    let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("FieldPartner", isDirectory: true)
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    stateURL = dir.appendingPathComponent("uploads.json")
    if let data = try? Data(contentsOf: stateURL), let saved = try? JSONDecoder().decode([Item].self, from: data) {
      items = saved
    }
    monitor.pathUpdateHandler = { [weak self] path in
      Task { await self?.pathChanged(path) }
    }
    monitor.start(queue: DispatchQueue(label: "fieldpartner.uploads.path"))
  }

  func configure(api: APIClient) {
    self.api = api
    // Recordings left from an earlier run upload as soon as the app is signed in again,
    // not only after the next network change.
    Task { await processQueue() }
  }

  var pendingCount: Int { items.count }

  private func pathChanged(_ path: NWPath) {
    online = path.status == .satisfied
    onWiFi = path.status == .satisfied && !path.isExpensive && !path.isConstrained
    if online { Task { await processQueue() } }
  }

  func enqueue(visitId: String, file: URL, kind: String, contentType: String) {
    guard FileManager.default.fileExists(atPath: file.path) else { return }
    let item = Item(visitId: visitId, path: file.path, kind: kind, contentType: contentType, clientId: "\(visitId)-\(file.lastPathComponent)", attempts: 0)
    if !items.contains(where: { $0.clientId == item.clientId }) {
      items.append(item)
      save()
    }
    Task { await processQueue() }
  }

  func processQueue() async {
    guard !running, let api, online else { return }
    running = true
    defer { running = false }
    let allowCellular = await MainActor.run { AppSettings.shared.uploadVideoOnCellular }
    var index = 0
    while index < items.count {
      let item = items[index]
      if item.kind == "video" && !onWiFi && !allowCellular {
        index += 1
        continue
      }
      let url = URL(fileURLWithPath: item.path)
      guard FileManager.default.fileExists(atPath: item.path) else {
        items.remove(at: index)
        save()
        continue
      }
      do {
        let size = (try? FileManager.default.attributesOfItem(atPath: item.path)[.size] as? Int) ?? 0
        let ticket = try await api.requestUpload(visitId: item.visitId, kind: item.kind, filename: url.lastPathComponent, contentType: item.contentType, bytes: size, clientId: item.clientId)
        if ticket.alreadyUploaded != true {
          // No usable upload address means nothing was stored: keep the file and retry later.
          guard let signed = ticket.url.flatMap(URL.init(string:)) else { throw APIError.badResponse }
          let target = APIClient.rewriteLocalhost(signed, apiBase: api.baseURL)
          try await api.upload(file: url, to: target, contentType: item.contentType)
          try await api.completeUpload(mediaId: ticket.mediaId, bytes: size)
        }
        items.remove(at: index)
        save()
        try? FileManager.default.removeItem(at: url)
      } catch {
        items[index].attempts += 1
        let attempts = items[index].attempts
        save()
        index += 1
        // Back off a little per failure so a dead network doesn't spin the radio.
        let wait = min(60, 1 << min(attempts, 6))
        try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000_000)
      }
    }
  }

  private func save() {
    if let data = try? JSONEncoder().encode(items) {
      try? data.write(to: stateURL, options: .atomic)
    }
  }
}
