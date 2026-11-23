---
name: iphone-install
description: Use when installing the FieldPartner iOS app on a real iPhone plugged into the user's Mac, with a free Apple ID or a paid Apple Developer team.
---

# Install on a real iPhone

Needs: a Mac with Xcode, Homebrew, the repo at `~/Documents/FieldPartner`, the iPhone on a USB cable.

## One command

`./scripts/install-iphone.sh` (generates the project, builds for the plugged-in phone, installs and opens it).
If the script is missing on this branch, do the manual steps below.

## Config: ios/Config/Local.xcconfig (never committed)

Copy from `Local.xcconfig.example` and set:
- `DEVELOPMENT_TEAM`: 10-character Team ID. Xcode > Settings > Accounts > the Apple ID > team. A free Apple ID shows "(Personal Team)".
- `FP_BUNDLE_ID`: unique, for example `com.ironrim.fieldpartner`. A free team cannot reuse an ID another team owns.
- `FP_API_BASE_URL`: `https:/$()/fieldpartner-api.onrender.com` (the `$()` stops xcconfig treating `//` as a comment).
- `META_APP_ID = 0`, `CLIENT_TOKEN = 0` while the Meta AI app has Developer Mode on.
- `FP_ENTITLEMENTS`: empty for a free team. Paid team: `FieldPartner/FieldPartner.entitlements`.

## Free vs paid Apple team

- Free (Personal Team): installs on your own devices only, at most 3 apps per device, and the app stops opening after 7 days (rebuild to renew). The Hotspot Configuration entitlement is not allowed, so the glasses connect over Bluetooth but send no video. Phone camera mode works.
- Paid ($99 a year, Individual is fastest): set `FP_ENTITLEMENTS` so the phone can join the Wi-Fi network the glasses host for camera video. Also allows TestFlight for techs (internal testers, no review).

## Manual steps

1. `brew install xcodegen` if missing.
2. `cd ios && xcodegen generate && open FieldPartner.xcodeproj`
3. Pick the iPhone at the top of Xcode, press Run.
4. On the iPhone, first time only:
   - Settings > Privacy & Security > Developer Mode > On, then restart the phone.
   - Tap "Trust" on the "Trust This Computer?" prompt.
   - After install: Settings > General > VPN & Device Management > the developer app > Trust.
5. Command-line build without opening Xcode:
   `xcodebuild -project ios/FieldPartner.xcodeproj -scheme FieldPartner -destination 'generic/platform=iOS' -allowProvisioningUpdates build`

## Check it worked

- The login screen shows `https://fieldpartner-api.onrender.com` as the company server.
- Join with an invite code from the portal (Team), finish setup in phone camera mode, run a walk-in visit.
- Glasses: see the glasses-debug skill.
