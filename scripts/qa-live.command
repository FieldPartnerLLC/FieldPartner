#!/bin/bash
# Double-click in Finder: runs the full live test (robot technician + backend checks) against the
# live server from this Mac, which can do WebRTC. Output: logs/qa-live-latest.txt
cd "$(dirname "$0")/.." || exit 1
mkdir -p logs
export FP_QA_CREDS="$PWD/logs/.qa-creds.json"
{
  echo "Started $(date)"
  agent/.venv/bin/python -u tools/qa_live.py --server https://fieldpartner-api.onrender.com
  echo "Exit code $?"
  echo "Finished $(date)"
} 2>&1 | tee logs/qa-live-latest.txt
