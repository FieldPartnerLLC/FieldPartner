// swift-tools-version:5.9
// Compiles and tests the platform-independent core of the iOS app with plain
// `swift test` (Linux or macOS, no Xcode): command parsing, the offline event
// queue, wire models and API errors. The full app builds from project.yml.
import PackageDescription

let package = Package(
  name: "FieldPartnerCore",
  // async URLSession APIs need macOS 12+; ignored on Linux. Matches the app's iOS 17.2 target.
  platforms: [.macOS(.v13), .iOS(.v17)],
  targets: [
    .target(
      name: "FieldPartner",
      path: "FieldPartner",
      sources: ["Core/Models.swift", "Core/APIClient.swift", "Core/EventQueue.swift", "Core/LiveVideoProfile.swift", "Core/AudioRoutePolicy.swift", "Speech/CommandParser.swift"]
    ),
    .testTarget(
      name: "FieldPartnerTests",
      dependencies: ["FieldPartner"],
      path: "FieldPartnerTests"
    ),
  ]
)
