// swift-tools-version:5.9
// Compiles and tests the platform-independent core of the iOS app with plain
// `swift test` (Linux or macOS, no Xcode): command parsing, the offline event
// queue, wire models and API errors. The full app builds from project.yml.
import PackageDescription

let package = Package(
  name: "FieldPartnerCore",
  targets: [
    .target(
      name: "FieldPartner",
      path: "FieldPartner",
      sources: ["Core/Models.swift", "Core/APIClient.swift", "Core/EventQueue.swift", "Speech/CommandParser.swift"]
    ),
    .testTarget(
      name: "FieldPartnerTests",
      dependencies: ["FieldPartner"],
      path: "FieldPartnerTests"
    ),
  ]
)
