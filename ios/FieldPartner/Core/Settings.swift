import Foundation
import Security

enum Keychain {
  private static let service = "com.ironrim.fieldpartner"

  static func set(_ value: String?, for key: String) {
    let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
    SecItemDelete(base as CFDictionary)
    guard let value, let data = value.data(using: .utf8) else { return }
    var add = base
    add[kSecValueData as String] = data
    add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
    SecItemAdd(add as CFDictionary, nil)
  }

  static func get(_ key: String) -> String? {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: key,
      kSecReturnData as String: true,
      kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    var out: AnyObject?
    guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
    return String(data: data, encoding: .utf8)
  }
}

/// User-adjustable settings, persisted in UserDefaults.
final class AppSettings: ObservableObject {
  static let shared = AppSettings()
  private let d = UserDefaults.standard

  @Published var apiBaseURL: String { didSet { d.set(apiBaseURL, forKey: "apiBaseURL") } }
  @Published var wakePhraseOverride: String { didSet { d.set(wakePhraseOverride, forKey: "wakePhraseOverride") } }
  @Published var aiVoiceVolume: Double { didSet { d.set(aiVoiceVolume, forKey: "aiVoiceVolume") } }
  @Published var uploadVideoOnCellular: Bool { didSet { d.set(uploadVideoOnCellular, forKey: "uploadVideoOnCellular") } }
  @Published var language: String { didSet { d.set(language, forKey: "language") } }
  @Published var usePhoneCamera: Bool { didSet { d.set(usePhoneCamera, forKey: "usePhoneCamera") } }
  @Published var showTranscript: Bool { didSet { d.set(showTranscript, forKey: "showTranscript") } }
  /// Id of the user who finished first-run setup on this phone. A different user sees setup again.
  @Published var setupDoneFor: String { didSet { d.set(setupDoneFor, forKey: "setupDoneFor") } }

  private init() {
    let bundled = (Bundle.main.object(forInfoDictionaryKey: "FPAPIBaseURL") as? String) ?? ""
    apiBaseURL = d.string(forKey: "apiBaseURL") ?? (bundled.isEmpty ? "http://192.168.1.20:8080" : bundled)
    wakePhraseOverride = d.string(forKey: "wakePhraseOverride") ?? ""
    aiVoiceVolume = d.object(forKey: "aiVoiceVolume") as? Double ?? 0.7
    uploadVideoOnCellular = d.bool(forKey: "uploadVideoOnCellular")
    language = d.string(forKey: "language") ?? "en-US"
    usePhoneCamera = d.bool(forKey: "usePhoneCamera")
    showTranscript = d.object(forKey: "showTranscript") as? Bool ?? true
    setupDoneFor = d.string(forKey: "setupDoneFor") ?? ""
  }
}

