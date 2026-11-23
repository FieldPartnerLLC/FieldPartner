#!/usr/bin/env bash
# One command to put FieldPartner on the iPhone plugged into this Mac.
#
#   ./scripts/install-iphone.sh                 # uses branch fix/ios-ci-swift-test
#   ./scripts/install-iphone.sh main            # or any branch
#   ./scripts/install-iphone.sh --no-checkout   # build whatever is checked out now
#
# Steps: checks Xcode and xcodegen, checks out the branch, writes ios/Config/Local.xcconfig
# the first time (asks for your Team ID), generates the Xcode project, builds for your iPhone,
# installs it and opens it.
set -euo pipefail

BRANCH="fix/ios-ci-swift-test"
CHECKOUT=1
for arg in "$@"; do
  case "$arg" in
    --no-checkout) CHECKOUT=0 ;;
    -h | --help)
      sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) BRANCH="$arg" ;;
  esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IOS="$ROOT/ios"
CONFIG="$IOS/Config/Local.xcconfig"
DERIVED="$ROOT/build/ios-device"
LOG="$ROOT/build/install-iphone.log"
mkdir -p "$ROOT/build"

say() { printf '\n==> %s\n' "$*"; }
fail() {
  printf '\nSTOPPED: %s\n' "$1" >&2
  shift
  for line in "$@"; do printf '  %s\n' "$line" >&2; done
  exit 1
}

# Plain-English help for the errors people actually hit. Reads the build/install log.
explain_failure() {
  local log="$1"
  echo >&2
  echo "What to do:" >&2
  local hit=0
  if grep -qiE "HotspotConfiguration|Personal development teams.*(do not|don't) support|does not support the Hotspot" "$log"; then
    hit=1
    echo "  - Your Apple team is a free Personal Team, and glasses video needs the paid (\$99) account." >&2
    echo "    Open ios/Config/Local.xcconfig, set the line to 'FP_ENTITLEMENTS =' (empty) and run this again." >&2
    echo "    The app installs and works with the phone camera; the glasses send no video until you upgrade." >&2
  fi
  if grep -qiE "No Accounts|No account for team|not signed in|requires a development team|Signing for .* requires a development team" "$log"; then
    hit=1
    echo "  - Xcode has no Apple ID for this team. Open Xcode > Settings > Accounts > + > Apple ID and sign in." >&2
    echo "    Then check the Team ID in ios/Config/Local.xcconfig matches the one shown there." >&2
  fi
  if grep -qiE "maximum number of (apps|registered)|exceeded the maximum|too many (apps|App IDs)|reached the maximum number of" "$log"; then
    hit=1
    echo "  - Free Apple ID limit: 3 apps per iPhone and 10 app IDs per 7 days." >&2
    echo "    Delete another app you installed from Xcode, or wait, or use the paid account." >&2
  fi
  if grep -qiE "Developer Mode|developer mode (is )?disabled|DeveloperMode" "$log"; then
    hit=1
    echo "  - Turn on Developer Mode on the iPhone: Settings > Privacy & Security > Developer Mode > On." >&2
    echo "    The iPhone restarts; unlock it and tap Turn On, then run this again." >&2
  fi
  if grep -qiE "not paired|pairing|trust|untrusted host|The device is locked|is locked" "$log"; then
    hit=1
    echo "  - Unlock the iPhone and tap Trust when it asks 'Trust This Computer?'. Keep it unlocked during install." >&2
  fi
  if grep -qiE "untrusted developer|not been explicitly trusted|profile has not been explicitly trusted|Unable to launch.*security|invalid code signature" "$log"; then
    hit=1
    echo "  - On the iPhone: Settings > General > VPN & Device Management > tap your Apple ID > Trust." >&2
    echo "    Then open FieldPartner from the home screen." >&2
  fi
  if grep -qiE "provisioning profile.*expired|profile has expired" "$log"; then
    hit=1
    echo "  - Free Apple ID builds expire after 7 days. Run this script again to reinstall." >&2
  fi
  if [ "$hit" = 0 ]; then
    echo "  - See the full log: $log" >&2
    echo "  - Most failures are fixed by: iPhone unlocked and trusted, Developer Mode on, Apple ID in Xcode > Settings > Accounts." >&2
  fi
}

# ---------- 1. Tools ----------
say "Checking Xcode and xcodegen"
[ "$(uname -s)" = "Darwin" ] || fail "This script runs on a Mac." "iPhone apps can only be built on macOS with Xcode."
command -v xcodebuild >/dev/null 2>&1 || fail "Xcode is not installed." "Install Xcode from the Mac App Store, open it once, then run this again."
if ! xcodebuild -version >/dev/null 2>&1; then
  fail "Xcode command line tools are not set up." \
    "Run: sudo xcode-select -s /Applications/Xcode.app/Contents/Developer" \
    "Then open Xcode once and accept the license."
fi
xcodebuild -version | head -1
if ! command -v xcodegen >/dev/null 2>&1; then
  command -v brew >/dev/null 2>&1 || fail "Homebrew is needed to install xcodegen." "Install it from https://brew.sh, then run this again."
  say "Installing xcodegen with Homebrew"
  brew install xcodegen
fi

# ---------- 2. Code ----------
cd "$ROOT"
if [ "$CHECKOUT" = 1 ]; then
  say "Getting branch $BRANCH"
  if ! git diff --quiet || ! git diff --cached --quiet; then
    fail "You have unsaved code changes in this folder." \
      "Commit or stash them first (git stash), or run with --no-checkout to build what is here."
  fi
  git fetch origin "$BRANCH"
  if git show-ref --verify --quiet "refs/heads/$BRANCH"; then
    git checkout "$BRANCH"
    git merge --ff-only "origin/$BRANCH"
  else
    git checkout -b "$BRANCH" "origin/$BRANCH"
  fi
  git log --oneline -1
fi

# ---------- 3. Local.xcconfig (first time only) ----------
if [ ! -f "$CONFIG" ]; then
  say "First-time setup: ios/Config/Local.xcconfig"
  echo "Your Apple Team ID is 10 letters and numbers, like ABCDE12345."
  echo "Find it in Xcode > Settings > Accounts > select your Apple ID > the Team row."
  echo "A free Apple ID shows a 'Personal Team' there, with its own ID. Paid accounts also show it at developer.apple.com > Membership."
  TEAM=""
  while ! printf '%s' "$TEAM" | grep -qE '^[A-Z0-9]{10}$'; do
    read -r -p "Team ID: " TEAM
    TEAM="$(printf '%s' "$TEAM" | tr '[:lower:]' '[:upper:]' | tr -d '[:space:]')"
    printf '%s' "$TEAM" | grep -qE '^[A-Z0-9]{10}$' || echo "That is not 10 letters and numbers. Try again."
  done
  DEFAULT_BUNDLE="com.$(id -un | tr -cd '[:alnum:]' | tr '[:upper:]' '[:lower:]').fieldpartner"
  read -r -p "App bundle ID [$DEFAULT_BUNDLE]: " BUNDLE
  BUNDLE="${BUNDLE:-$DEFAULT_BUNDLE}"
  echo "Glasses video on iPhone needs the paid Apple Developer account (\$99)."
  read -r -p "Is this Team ID a paid Apple Developer account? [y/N]: " PAID
  ENT=""
  case "$PAID" in y | Y | yes | YES) ENT="FieldPartner/FieldPartner.entitlements" ;; esac
  cat >"$CONFIG" <<EOF
// Written by scripts/install-iphone.sh. Not committed. Edit and rerun the script to change.
DEVELOPMENT_TEAM = $TEAM
FP_BUNDLE_ID = $BUNDLE
// The \$() keeps xcconfig from treating // as a comment.
FP_API_BASE_URL = https:/\$()/fieldpartner-api.onrender.com
META_APP_ID = 0
CLIENT_TOKEN = 0
// Glasses Wi-Fi video. Paid team: FieldPartner/FieldPartner.entitlements. Free Personal Team: leave empty.
FP_ENTITLEMENTS = $ENT
EOF
  echo "Saved $CONFIG"
else
  say "Using existing ios/Config/Local.xcconfig"
  grep -q "DEVELOPMENT_TEAM = ABCDE12345" "$CONFIG" && fail "ios/Config/Local.xcconfig still has the example Team ID." "Delete the file and run this again, or put your real Team ID in it."
  grep -q "^FP_ENTITLEMENTS" "$CONFIG" || printf '\n// Glasses Wi-Fi video. Paid team: FieldPartner/FieldPartner.entitlements. Free team: empty.\nFP_ENTITLEMENTS =\n' >>"$CONFIG"
fi
grep -E "^(DEVELOPMENT_TEAM|FP_BUNDLE_ID|FP_API_BASE_URL|FP_ENTITLEMENTS)" "$CONFIG"

# ---------- 4. Xcode project ----------
say "Generating the Xcode project"
(cd "$IOS" && xcodegen generate --quiet)

# ---------- 5. Find the iPhone ----------
say "Looking for your iPhone"
DEVICE_ID=""   # devicectl identifier (install/launch)
DEVICE_UDID="" # xcodebuild destination id (registers the phone for signing)
DEVICE_NAME=""
JSON="$ROOT/build/devices.json"
if xcrun devicectl list devices --json-output "$JSON" >/dev/null 2>&1; then
  read -r DEVICE_ID DEVICE_UDID DEVICE_NAME < <(/usr/bin/python3 - "$JSON" <<'PY' || true
import json, sys
d = json.load(open(sys.argv[1]))
best = None
for dev in d.get("result", {}).get("devices", []):
    hw = dev.get("hardwareProperties", {})
    if hw.get("platform") != "iOS" or hw.get("deviceType") != "iPhone":
        continue
    conn = dev.get("connectionProperties", {})
    paired = conn.get("pairingState") == "paired"
    wired = conn.get("transportType") == "wired"
    score = (paired, wired)
    if best is None or score > best[0]:
        best = (score, dev.get("identifier", ""), hw.get("udid", "") or dev.get("identifier", ""),
                dev.get("deviceProperties", {}).get("name", "iPhone"), paired)
if best:
    if not best[4]:
        print("UNPAIRED", "-", best[3])
    else:
        print(best[1], best[2], best[3])
PY
  )
fi
if [ "$DEVICE_ID" = "UNPAIRED" ]; then
  fail "Found $DEVICE_NAME but it does not trust this Mac yet." \
    "Unlock the iPhone, tap Trust on 'Trust This Computer?', enter the passcode, then run this again."
fi
if [ -z "$DEVICE_ID" ]; then
  # Fallback for older Xcode: xctrace lists real devices with their UDID in brackets.
  LINE="$(xcrun xctrace list devices 2>/dev/null | sed -n '/== Devices ==/,/== Simulators ==/p' | grep -E 'iPhone' | grep -v Simulator | head -1 || true)"
  if [ -n "$LINE" ]; then
    DEVICE_ID="$(printf '%s' "$LINE" | sed -E 's/.*\(([0-9A-Fa-f-]+)\)$/\1/')"
    DEVICE_UDID="$DEVICE_ID"
    DEVICE_NAME="$(printf '%s' "$LINE" | sed -E 's/ \(.*//')"
  fi
fi
[ -n "$DEVICE_ID" ] || fail "No iPhone found." \
  "Plug the iPhone into the Mac with a cable, unlock it, and tap Trust if it asks." \
  "Turn on Developer Mode: iPhone Settings > Privacy & Security > Developer Mode (it restarts)." \
  "Then run this again."
echo "Using $DEVICE_NAME ($DEVICE_ID)"

# ---------- 6. Build ----------
say "Building FieldPartner for $DEVICE_NAME (first build downloads packages, about 5 to 10 minutes)"
: >"$LOG"
set +e
xcodebuild -project "$IOS/FieldPartner.xcodeproj" -scheme FieldPartner -configuration Debug \
  -destination "id=$DEVICE_UDID" -derivedDataPath "$DERIVED" \
  -allowProvisioningUpdates -allowProvisioningDeviceRegistration \
  build >>"$LOG" 2>&1
BUILD_STATUS=$?
set -e
grep -E "error:|\*\* BUILD" "$LOG" | tail -20 || true
if [ "$BUILD_STATUS" != 0 ]; then
  explain_failure "$LOG"
  fail "The build failed." "Full log: $LOG"
fi
APP="$DERIVED/Build/Products/Debug-iphoneos/FieldPartner.app"
[ -d "$APP" ] || fail "Build finished but FieldPartner.app was not found." "Full log: $LOG"
BUNDLE_ID="$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$APP/Info.plist")"

# ---------- 7. Install and open ----------
say "Installing on $DEVICE_NAME"
set +e
xcrun devicectl device install app --device "$DEVICE_ID" "$APP" >>"$LOG" 2>&1
INSTALL_STATUS=$?
set -e
if [ "$INSTALL_STATUS" != 0 ]; then
  tail -15 "$LOG"
  explain_failure "$LOG"
  fail "Install failed." "Full log: $LOG"
fi

say "Opening FieldPartner"
set +e
xcrun devicectl device process launch --device "$DEVICE_ID" "$BUNDLE_ID" >>"$LOG" 2>&1
LAUNCH_STATUS=$?
set -e
if [ "$LAUNCH_STATUS" != 0 ]; then
  tail -8 "$LOG"
  explain_failure "$LOG"
  echo
  echo "FieldPartner is installed but did not open by itself. Fix the item above, then tap the app on the iPhone."
  exit 0
fi

cat <<EOF

DONE: FieldPartner is installed and open on $DEVICE_NAME.
  Server: the live server (fieldpartner-api.onrender.com).
  Next: tap "I have an invite code", or sign in.
  Free Apple ID: the app stops opening after 7 days. Run this script again to reinstall.
EOF
