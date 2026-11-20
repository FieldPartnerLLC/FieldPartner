#!/usr/bin/env bash
# Starts the backend, AI agent and expert portal on this Mac. Ctrl+C stops all three.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
[ -f config.env ] || { echo "Run ./scripts/setup-mac.sh first."; exit 1; }
IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "127.0.0.1")
sed -i '' "s|^PUBLIC_API_URL=.*|PUBLIC_API_URL=http://$IP:8080|" config.env
sed -i '' "s|^PORTAL_URL=.*|PORTAL_URL=http://$IP:5173|" config.env
set -a; source config.env; set +a
export BACKEND_URL="http://127.0.0.1:8080" DATA_DIR="$ROOT/data" VITE_API_URL="http://$IP:8080"
for k in LIVEKIT_URL LIVEKIT_API_KEY LIVEKIT_API_SECRET GOOGLE_API_KEY; do
  if [ -z "${!k:-}" ] || [[ "${!k}" == *your-project* ]]; then echo "Fill in $k in config.env first (open -e config.env)"; exit 1; fi
done
mkdir -p logs data

if [ ! -f data/.seeded ]; then
  echo "Creating the database and demo accounts..."
  (cd backend && npx tsx src/seed.ts) | tee data/demo-accounts.txt
  touch data/.seeded
fi

pids=()
cleanup() { echo; echo "Stopping..."; kill "${pids[@]}" 2>/dev/null || true; wait 2>/dev/null || true; }
trap cleanup EXIT INT TERM

(cd backend && npx tsx src/index.ts) > logs/backend.log 2>&1 & pids+=($!)
sleep 3
curl -sf http://127.0.0.1:8080/healthz >/dev/null || { echo "Backend failed to start. See logs/backend.log"; tail -30 logs/backend.log; exit 1; }
(cd agent && ./.venv/bin/python main.py dev) > logs/agent.log 2>&1 & pids+=($!)
(cd portal && npx vite --host --port 5173) > logs/portal.log 2>&1 & pids+=($!)
sleep 4

cat <<MSG

FieldPartner is running on this Mac.

  Server for the iPhone app:  http://$IP:8080
  Expert portal:              http://$IP:5173   (open on this Mac or any laptop on the same Wi-Fi)
  Demo logins:                $ROOT/data/demo-accounts.txt
  Logs:                       $ROOT/logs/

The iPhone must be on the same Wi-Fi as this Mac.
If macOS asks whether "node" may accept incoming connections, click Allow.
Press Ctrl+C to stop.
MSG
tail -f logs/agent.log logs/backend.log
