#!/usr/bin/env bash
# Checks the live system. With EXPECT_COMMIT set, first waits (up to 25 minutes) for Render to run it.
set -euo pipefail
API="${LIVE_API:-https://fieldpartner-api.onrender.com}"
PORTAL="${LIVE_PORTAL:-https://fieldpartner-portal.onrender.com}"
health() { curl -fsS -m 30 "$API/healthz"; }
if [ -n "${EXPECT_COMMIT:-}" ]; then
  for i in $(seq 1 50); do
    got=$(health | python3 -c 'import json,sys; print(json.load(sys.stdin).get("commit") or "")' 2>/dev/null || true)
    if [ "$got" = "$EXPECT_COMMIT" ]; then echo "Render is running $got"; break; fi
    if [ -z "$got" ] && [ "$i" -gt 2 ]; then echo "Server does not report its commit yet; checking health only."; break; fi
    echo "Waiting for deploy ($i/50): live=${got:-unknown} expected=$EXPECT_COMMIT"; sleep 30
  done
fi
H=$(health); echo "$H"
echo "$H" | python3 -c '
import json, sys
h = json.load(sys.stdin)
need = ["ok", "livekit", "gemini", "email"]
bad = [k for k in need if not h.get(k)]
print("optional:", {k: h.get(k) for k in ("claude", "twilio")})
sys.exit("FAIL: not ready: " + ", ".join(bad) if bad else 0)
'
code=$(curl -s -o /dev/null -w "%{http_code}" -m 30 "$PORTAL/")
[ "$code" = "200" ] || { echo "FAIL: portal returned $code"; exit 1; }
echo "PASS: live API and portal are healthy"
