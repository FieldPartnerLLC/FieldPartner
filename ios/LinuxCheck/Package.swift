// swift-tools-version:5.9
// Compiles and tests the platform-independent parts of the iOS app on Linux/macOS
// without Xcode: command parsing, the offline event queue, wire models, API errors.
import PackageDescription

let package = Package(
  name: "FieldPartnerLinuxCheck",
  targets: [
    .target(name: "FieldPartner", path: "Sources/FieldPartner"),
    .testTarget(name: "FieldPartnerTests", dependencies: ["FieldPartner"], path: "Tests/FieldPartnerTests"),
  ]
)
