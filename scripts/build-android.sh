#!/usr/bin/env bash
# Builds the Android app.
#   ./scripts/build-android.sh                     debug build, install and launch on the emulator or USB phone
#   ./scripts/build-android.sh phone [SERVER_URL]  APK for a phone: https URL -> FieldPartner-android-online.apk, Mac IP -> -offline.apk
#                                                  e.g. ./scripts/build-android.sh phone https://fieldpartner-api.onrender.com
#   ./scripts/build-android.sh release             signed Play Store bundle (.aab), needs a keystore (docs/EMULATOR-AND-STORES.md)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/android"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
# Android Studio ships its own Java; use it if the Mac has no other.
if ! /usr/libexec/java_home -v 17+ >/dev/null 2>&1 && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
[ -f local.properties ] || { echo "Run ./scripts/setup-mac.sh first."; exit 1; }

set_prop() { if grep -q "^$1=" local.properties; then sed -i '' "s|^$1=.*|$1=$2|" local.properties; else echo "$1=$2" >> local.properties; fi; }

if [ "${1:-debug}" = "phone" ]; then
  if [ -n "${2:-}" ]; then set_prop fp_api_base_url "${2%/}"; fi
  set_prop fp_abi_filters arm64-v8a
  ./gradlew assembleDebug
  sed -i '' "/^fp_abi_filters=/d" local.properties
  URL_NOW="$(grep '^fp_api_base_url=' local.properties | cut -d= -f2-)"
  case "$URL_NOW" in https://*) OUT="FieldPartner-android-online.apk" ;; *) OUT="FieldPartner-android-offline.apk" ;; esac
  cp app/build/outputs/apk/debug/app-debug.apk "$ROOT/$OUT"
  echo; echo "Phone APK: $ROOT/$OUT"
  echo "Server built in: $(grep '^fp_api_base_url=' local.properties | cut -d= -f2-)"
  echo "Send it to the phone (Google Drive, email, or USB) and open it there to install."
  exit 0
fi

if [ "${1:-debug}" = "release" ]; then
  ./gradlew bundleRelease
  echo; echo "Play Store bundle: $ROOT/android/app/build/outputs/bundle/release/app-release.aab"
  exit 0
fi

./gradlew assembleDebug
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
if "$ADB" get-state >/dev/null 2>&1; then
  "$ADB" install -r "$APK"
  # Grant the runtime permissions so the first test run has no pop-ups.
  for p in RECORD_AUDIO CAMERA ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS BLUETOOTH_CONNECT; do
    "$ADB" shell pm grant com.fieldpartner.app android.permission.$p 2>/dev/null || true
  done
  "$ADB" shell am start -n com.fieldpartner.app/.MainActivity
  echo "Installed and launched on $("$ADB" get-serialno)."
else
  echo "Built $APK"
  echo "No emulator or phone connected. Start the emulator in Android Studio (Device Manager > play button), then run this again."
fi
