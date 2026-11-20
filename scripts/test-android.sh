#!/usr/bin/env bash
# Runs the Android test suite: logic, API, UI flows (Robolectric) and, if the local backend
# is running, the full visit flow against it.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/android"
if ! /usr/libexec/java_home -v 17+ >/dev/null 2>&1 && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
if curl -sf http://127.0.0.1:8080/healthz >/dev/null 2>&1 && [ -f "$ROOT/data/demo-accounts.txt" ]; then
  export FP_BACKEND_URL=http://127.0.0.1:8080
  export FP_TECH_PASSWORD="$(grep -E 'Password:' "$ROOT/data/demo-accounts.txt" | awk '{print $2}')"
  echo "Backend is running: the contract test will run a full visit against it."
fi
./gradlew testDebugUnitTest
echo "All Android tests passed. Report: $ROOT/android/app/build/reports/tests/testDebugUnitTest/index.html"
