#!/usr/bin/env bash
# Picks the newest iPhone simulator on this Mac, builds the app, runs its unit tests, then installs
# and opens it and fails if it is not running after 15 seconds. Screenshot and logs go to ci-out/.
set -euo pipefail
mkdir -p ci-out
read -r UDID NAME < <(xcrun simctl list devices available -j | python3 -c '
import json, re, sys
d = json.load(sys.stdin)["devices"]
def ver(rt):
    m = re.search(r"iOS-(\d+)-(\d+)", rt)
    return (int(m.group(1)), int(m.group(2))) if m else (0, 0)
best = None
for rt in sorted((r for r in d if "iOS" in r), key=ver, reverse=True):
    phones = [x for x in d[rt] if x["name"].startswith("iPhone") and "SE" not in x["name"]]
    def rank(x):
        n = re.search(r"iPhone (\d+)", x["name"])
        return (int(n.group(1)) if n else 0, "Pro" in x["name"], "Max" not in x["name"])
    if phones:
        best = max(phones, key=rank)
        print(best["udid"], best["name"] + " (" + rt.split(".")[-1] + ")")
        break
if not best:
    sys.exit("no iPhone simulator found")
')
echo "Using $NAME ($UDID)"
xcodebuild -project ios/FieldPartner.xcodeproj -scheme FieldPartner \
  -destination "id=$UDID" -derivedDataPath build/ios \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  test 2>&1 | tee ci-out/xcodebuild.log | grep -E "error:|warning: unre|Test Suite|Executed|BUILD|\*\*" || true
grep -q "TEST SUCCEEDED\|\*\* TEST SUCCEEDED \*\*" ci-out/xcodebuild.log || { grep -E "error:" ci-out/xcodebuild.log | head -40; echo "FAIL: build or tests failed"; exit 1; }
APP=$(ls -d build/ios/Build/Products/Debug-iphonesimulator/FieldPartner.app)
xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl install "$UDID" "$APP"
BUNDLE=$(/usr/libexec/PlistBuddy -c "Print CFBundleIdentifier" "$APP/Info.plist")
xcrun simctl launch "$UDID" "$BUNDLE"
sleep 15
xcrun simctl io "$UDID" screenshot ci-out/ios-launch.png
xcrun simctl spawn "$UDID" launchctl list | grep -q "$BUNDLE" || { echo "FAIL: the app is not running (crashed on launch)"; exit 1; }
echo "PASS: built, tested, installed and opened on $NAME"

# Meta Wearables SDK status from the app's own log (a warning, never a failure: the app runs without it).
xcrun simctl spawn "$UDID" log show --last 3m --style compact --predicate 'eventMessage CONTAINS "[FieldPartner]"' > ci-out/app-log.txt 2>/dev/null || true
grep -h "Wearables SDK configure failed" ci-out/xcodebuild.log ci-out/app-log.txt 2>/dev/null | head -1 | sed 's/^/WARNING: /' || true
grep -h "Wearables SDK configured\|Simulated glasses" ci-out/app-log.txt 2>/dev/null | sed 's/^.*\[FieldPartner\]/INFO: [FieldPartner]/' | sort -u || true

# One-off probe: which build settings let Wearables.configure() succeed on the simulator.
if [ "${FP_SDK_PROBE:-0}" = "1" ]; then
  set +e +o pipefail
  cat > ci-out/probe-ent.plist <<'PL'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>application-identifier</key><string>FPCI000000.com.ironrim.fieldpartner</string>
  <key>keychain-access-groups</key><array><string>FPCI000000.com.ironrim.fieldpartner</string></array>
</dict></plist>
PL
  probe() {
    local name="$1"; shift
    echo "=== PROBE $name: $*"
    xcrun simctl uninstall "$UDID" "$BUNDLE" 2>/dev/null || true
    if ! xcodebuild -project ios/FieldPartner.xcodeproj -scheme FieldPartner -destination "id=$UDID" \
        -derivedDataPath build/ios-probe "$@" build > "ci-out/probe-$name.log" 2>&1; then
      echo "PROBE $name: BUILD FAILED"; grep -E "error:" "ci-out/probe-$name.log" | head -5; return 0
    fi
    local app; app=$(ls -d build/ios-probe/Build/Products/Debug-iphonesimulator/FieldPartner.app)
    codesign -d --entitlements :- "$app" 2>/dev/null | tr -d '\n' | head -c 400; echo
    /usr/libexec/PlistBuddy -c "Print :MWDAT" "$app/Info.plist" 2>/dev/null | tr '\n' ' '; echo
    xcrun simctl install "$UDID" "$app"
    xcrun simctl launch "$UDID" "$BUNDLE" >/dev/null
    sleep 12
    xcrun simctl spawn "$UDID" log show --last 20s --style compact --predicate 'eventMessage CONTAINS "[FieldPartner]" OR eventMessage CONTAINS "MWDAT" OR eventMessage CONTAINS "access group" OR eventMessage CONTAINS "attestation"' 2>/dev/null \
      | grep -v "^Timestamp" | cut -c1-260 | tail -12 | sed "s/^/PROBE $name | /"
    xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
  }
  probe A_unsigned_noteam CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO DEVELOPMENT_TEAM=
  probe B_unsigned_team CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO DEVELOPMENT_TEAM=FPCI000000
  probe C_adhoc_noteam CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER=
  probe D_adhoc_team CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM=FPCI000000 PROVISIONING_PROFILE_SPECIFIER=
  probe E_adhoc_team_keychain CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM=FPCI000000 PROVISIONING_PROFILE_SPECIFIER= CODE_SIGN_ENTITLEMENTS="$PWD/ci-out/probe-ent.plist"
  echo "=== PROBE done"
fi
