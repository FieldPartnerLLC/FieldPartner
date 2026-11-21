#!/usr/bin/env bash
# Installs the APK on the running emulator, opens it, and fails if it crashes or the sign-in screen
# does not appear. Leaves a screenshot, the screen's layout and the log in ci-out/.
set -euo pipefail
APK="${1:?apk path}"
PKG="${FP_APPLICATION_ID:-com.fieldpartner.app}"
mkdir -p ci-out
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
adb logcat -c || true
adb install -r -g "$APK"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 15
adb exec-out screencap -p > ci-out/android-launch.png
adb shell uiautomator dump /sdcard/ui.xml >/dev/null && adb pull /sdcard/ui.xml ci-out/android-ui.xml >/dev/null
adb logcat -d > ci-out/android-logcat.txt || true
fail() { echo "FAIL: $1"; exit 1; }
[ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ] || fail "the app is not running (crashed on launch)"
if grep -q "FATAL EXCEPTION" ci-out/android-logcat.txt && grep -A3 "FATAL EXCEPTION" ci-out/android-logcat.txt | grep -q "$PKG"; then
  grep -A20 "FATAL EXCEPTION" ci-out/android-logcat.txt | head -40
  fail "the app crashed"
fi
grep -q 'FieldPartner' ci-out/android-ui.xml || fail "the sign-in screen did not appear"
grep -q 'I have an invite code' ci-out/android-ui.xml || fail "the invite button is missing"
echo "PASS: app installed, opened and showed the sign-in screen on $(adb shell getprop ro.product.model | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
