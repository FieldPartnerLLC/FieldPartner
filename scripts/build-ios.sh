#!/usr/bin/env bash
# Generates the Xcode project and opens it. Then: pick your iPhone at the top of Xcode and press Run.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/ios"
command -v xcodegen >/dev/null || { echo "Run: brew install xcodegen"; exit 1; }
[ -f Config/Local.xcconfig ] || cp Config/Local.xcconfig.example Config/Local.xcconfig
if grep -q "DEVELOPMENT_TEAM = ABCDE12345" Config/Local.xcconfig; then
  echo "Set your Team ID in ios/Config/Local.xcconfig (DEVELOPMENT_TEAM)."
  echo "Find it in Xcode > Settings > Accounts > your Apple ID > Team, or developer.apple.com > Membership."
  echo "Also set FP_BUNDLE_ID to something unique, e.g. com.yourname.fieldpartner"
  exit 1
fi
xcodegen generate
open FieldPartner.xcodeproj
