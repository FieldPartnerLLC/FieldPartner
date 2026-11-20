#!/usr/bin/env bash
# One-time setup on a Mac. Safe to run again.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

say() { printf "\n\033[1m%s\033[0m\n" "$*"; }
fail() { printf "\n\033[31mERROR: %s\033[0m\n" "$*"; exit 1; }

say "Checking tools"
command -v node >/dev/null || fail "Node.js is missing. Install it: brew install node"
NODE_MAJOR=$(node -p 'process.versions.node.split(".")[0]')
[ "$NODE_MAJOR" -ge 20 ] || fail "Node.js 20 or newer is needed (you have $(node -v)). Run: brew install node"
PY=""
for c in python3.12 python3.13 python3.11 python3; do
  if command -v $c >/dev/null && $c -c 'import sys; sys.exit(0 if sys.version_info>=(3,10) else 1)'; then PY=$c; break; fi
done
[ -n "$PY" ] || fail "Python 3.10+ is missing. Run: brew install python@3.12"
echo "node $(node -v), $($PY --version)"
command -v xcodegen >/dev/null || echo "Note: xcodegen not found. Run: brew install xcodegen (needed for the iPhone app)"

say "Settings file"
[ -f config.env ] || cp config.env.example config.env
IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "127.0.0.1")
set_kv() { # key value: set if empty
  local k="$1" v="$2"
  if grep -q "^$k=$" config.env; then sed -i '' "s|^$k=$|$k=$v|" config.env; fi
}
set_kv JWT_SECRET "$(openssl rand -hex 32)"
set_kv AGENT_SERVICE_TOKEN "$(openssl rand -hex 24)"
# Always refresh the network address: it changes when the Mac changes Wi-Fi.
sed -i '' "s|^PUBLIC_API_URL=.*|PUBLIC_API_URL=http://$IP:8080|" config.env
sed -i '' "s|^PORTAL_URL=.*|PORTAL_URL=http://$IP:5173|" config.env
echo "This Mac's Wi-Fi address: $IP"

say "Backend"
(cd backend && npm install --no-audit --no-fund)

say "Expert portal"
(cd portal && npm install --no-audit --no-fund)

say "AI agent (Python)"
(cd agent && $PY -m venv .venv && ./.venv/bin/pip install -q --upgrade pip && ./.venv/bin/pip install -q -r requirements-dev.txt)

say "iPhone app settings"
if [ ! -f ios/Config/Local.xcconfig ]; then cp ios/Config/Local.xcconfig.example ios/Config/Local.xcconfig; fi
sed -i '' "s|^FP_API_BASE_URL = .*|FP_API_BASE_URL = http:/\$()/$IP:8080|" ios/Config/Local.xcconfig

say "Android app settings"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
touch android/local.properties
set_prop() { # key value: replace or append in android/local.properties
  if grep -q "^$1=" android/local.properties; then sed -i '' "s|^$1=.*|$1=$2|" android/local.properties; else echo "$1=$2" >> android/local.properties; fi
}
set_prop sdk.dir "$SDK"
set_prop fp_api_base_url "http://$IP:8080"
[ -d "$SDK" ] || echo "Note: Android SDK not found at $SDK. Install Android Studio, open it once, then run this script again."

say "Running the automated tests"
(cd backend && npm test --silent) || fail "Backend tests failed"
(cd agent && ./.venv/bin/python -m pytest -q) || fail "Agent tests failed"

MISSING=""
grep -q "^LIVEKIT_API_KEY=$" config.env && MISSING="$MISSING LIVEKIT_API_KEY"
grep -q "^LIVEKIT_API_SECRET=$" config.env && MISSING="$MISSING LIVEKIT_API_SECRET"
grep -q "^LIVEKIT_URL=wss://your-project" config.env && MISSING="$MISSING LIVEKIT_URL"
grep -q "^GOOGLE_API_KEY=$" config.env && MISSING="$MISSING GOOGLE_API_KEY"
if [ -n "$MISSING" ]; then
  say "Almost done. Open config.env and fill in:$MISSING"
  echo "  open -e $ROOT/config.env"
else
  say "Setup complete. Start everything with: ./scripts/start-local.sh"
fi
