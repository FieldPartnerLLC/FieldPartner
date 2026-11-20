import Foundation

/// Visit events are written to disk first and delivered later, so nothing is lost
/// when the phone goes into a basement, the app is killed, or the server is down.
/// Delivery is at-least-once; the server de-duplicates by event id.
actor EventQueue {
  private let fileURL: URL
  private var pending: [VisitEvent] = []
  private var flushing = false
  private(set) var lastError: String?

  init(visitId: String, directory: URL = EventQueue.defaultDirectory) {
    try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    fileURL = directory.appendingPathComponent("events-\(visitId).json")
    if let data = try? Data(contentsOf: fileURL), let saved = try? JSONDecoder().decode([VisitEvent].self, from: data) {
      pending = saved
    }
  }

  static var defaultDirectory: URL {
    FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("FieldPartner/queues", isDirectory: true)
  }

  var count: Int { pending.count }

  func add(_ event: VisitEvent) {
    pending.append(event)
    persist()
  }

  /// Sends pending events in batches. Returns how many were delivered.
  @discardableResult
  func flush(using send: ([VisitEvent]) async throws -> Void) async -> Int {
    guard !flushing, !pending.isEmpty else { return 0 }
    flushing = true
    defer { flushing = false }
    var delivered = 0
    while !pending.isEmpty {
      let batch = Array(pending.prefix(200))
      do {
        try await send(batch)
        let ids = Set(batch.map(\.id))
        pending.removeAll { ids.contains($0.id) }
        delivered += batch.count
        lastError = nil
        persist()
      } catch let e as APIError where !e.isTransient && e != .notSignedIn {
        // The server rejected this batch outright; retrying would loop forever.
        lastError = e.localizedDescription
        let ids = Set(batch.map(\.id))
        pending.removeAll { ids.contains($0.id) }
        persist()
      } catch {
        lastError = error.localizedDescription
        break
      }
    }
    return delivered
  }

  func discardFile() {
    try? FileManager.default.removeItem(at: fileURL)
  }

  private func persist() {
    do {
      let data = try JSONEncoder().encode(pending)
      #if os(iOS)
      try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      #else
      try data.write(to: fileURL, options: [.atomic])
      #endif
    } catch {
      NSLog("[EventQueue] persist failed: %@", error.localizedDescription)
    }
  }
}
