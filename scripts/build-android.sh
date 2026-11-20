#!/usr/bin/env bash
# Builds the Android app and installs it on the running emulator or a USB-connected phone.
#   ./scripts/build-android.sh           debug build, install, launch
#   ./scripts/build-android.sh release   signed Play Store bundle (.aab), needs a keystore (see docs)
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
