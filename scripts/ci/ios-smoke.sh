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
# Ad-hoc signing ("-", no team needed): an unsigned simulator app has no keychain entitlement, and
# Meta's Wearables.configure() then fails with internalError, so simulated glasses never pair.
xcodebuild -project ios/FieldPartner.xcodeproj -scheme FieldPartner \
  -destination "id=$UDID" -derivedDataPath build/ios \
  CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= \
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

