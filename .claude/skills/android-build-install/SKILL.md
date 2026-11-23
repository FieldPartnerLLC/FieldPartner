---
name: android-build-install
description: Use when building, testing or installing the FieldPartner Android app on the emulator or a real Android phone.
---

# Android build, test, install

Project: `android/` (AGP 8.11, Kotlin 2.2, Compose, minSdk 31, so Android 12 or newer). First time on a Mac: `./scripts/setup-mac.sh` (creates `android/local.properties`).

## Commands

- Emulator or USB phone, debug build, installs and launches with permissions granted:
  `./scripts/build-android.sh`
- APK file for a phone, pointed at the live server:
  `./scripts/build-android.sh phone https://fieldpartner-api.onrender.com`
  Output: `FieldPartner-android-online.apk` in the repo root. An http Mac IP gives `-offline.apk`.
- Tests (logic, API, Robolectric screens, plus the visit flow if the local backend runs):
  `./scripts/test-android.sh`
- Java older than 17: the scripts switch to Android Studio's bundled JDK automatically.

## Without a Mac

The CI job "Android build and tests" builds the APK and uploads it as the `android-apk` artifact (30 days):
```
RUN=$(gh run list -R FieldPartnerLLC/fieldpartner --limit 1 --json databaseId -q '.[0].databaseId')
gh run download $RUN -R FieldPartnerLLC/fieldpartner -n android-apk
```
On main, when every job passes, the "Publish the latest APK" job replaces the GitHub release named `latest`:
`gh release download latest -R FieldPartnerLLC/fieldpartner -p FieldPartner-android.apk`

## Sideload on a phone

1. Get the APK onto the phone: Google Drive, email, or USB. AirDrop does not work with Android.
2. Tap it and allow "Install unknown apps" for that app when asked.
3. No Play Store account is needed. Google's developer verification for sideloaded apps starts in 4 countries on Sept 30, 2026 and goes global in 2027; plan for it then.

## Notes

- During a call WebRTC owns the mic. `audio/MicBus` taps its samples; never open a second AudioRecord.
- Android 12 has no SpeechRecognizer audio source, so it falls back to the Talk button and typed questions.
- Pure logic lives in `app/src/main/java/com/fieldpartner/app/core/` with JVM tests. Keep Android imports out.
