# FieldPartner

AI partner for field technicians in any trade: HVAC, electrical, plumbing, roofing, cleaning, auto, appliance and general service. The technician wears Meta AI glasses; the phone app streams what they see to an AI partner (Gemini Live) and, on request, to a human expert who can watch and talk. Every visit is recorded with customer consent, transcribed, and turned into a service report.

## What's in this folder

| Folder | What it is | Tested |
|---|---|---|
| `backend/` | API server (Node/TypeScript). Jobs, visits, consent, events, escalation paging, reports, uploads, LiveKit tokens. Runs with an embedded database on a laptop or Postgres in production. | 45 automated scenario tests, run on both the embedded database and Postgres 16 |
| `agent/` | AI agent worker (Python, LiveKit Agents, Gemini Live). Silent until called, trade-specific safety rules, background hazard watcher, mutes when an expert joins. | 21 automated tests |
| `portal/` | Expert web portal (React). Live board, join a visit with video and two-way audio, snapshots, history, review queue, team admin. | Type-checked and built |
| `ios/` | iPhone app (SwiftUI, Meta Wearables Device Access Toolkit 1.1, LiveKit). | Core logic: 19 tests on Linux. Full app: must be compiled in Xcode on a Mac |
| `shared/trades.json` | Trade profiles: required readings, stop rules, never-guide steps, hazards, verification checklists. | Used by tests |
| `scripts/` | One-command setup, start and iOS build on a Mac. | |
| `docs/` | Architecture, field test checklist, production deployment. | |

Android is not built yet. It is the next phase; it uses the same backend, agent and portal.

## Tonight: test on your MacBook with your glasses

You need: the MacBook with Xcode installed and signed in, your iPhone on the same Wi-Fi, a free LiveKit Cloud project, and a Gemini API key.

1. In Terminal:
   ```
   cd ~/Documents/FieldPartner
   ./scripts/setup-mac.sh
   ```
2. Open `config.env` (`open -e config.env`) and paste your LiveKit URL, API key, API secret, and Gemini key. Save.
3. Start the servers. Leave this window open:
   ```
   ./scripts/start-local.sh
   ```
   It prints the expert portal address and where the demo logins are saved.
4. In a second Terminal window, set your Apple Team ID and a unique bundle id in `ios/Config/Local.xcconfig`, then:
   ```
   ./scripts/build-ios.sh
   ```
   Xcode opens. Pick your iPhone at the top, press Run (the play button). The first build downloads packages and takes a few minutes.
   If the build fails, open Claude Code in this folder and say: "Build the iOS app in ios/ and fix the compile errors." See `CLAUDE.md`.
5. On the iPhone: allow microphone, speech recognition and location. Sign in with the technician demo login. The server address is filled in.
6. Glasses: in the Meta AI app, Developer Mode on (Settings > App Info > tap the version 5 times). In FieldPartner, tap Connect on the glasses row; Meta AI opens to approve.
   No glasses yet? Settings > "Use phone camera instead of glasses", or "Use simulated glasses".
7. Open a job, Start visit, go through the checks, then:
   - Say the wake phrase ("hey dispatch") and a question while looking at something.
   - Tap "Get customer consent" and sign as a test customer.
   - Tap Expert. On the MacBook, open the portal, sign in as the expert, click "Join live". You'll see the glasses video and can talk.
   - End the visit and review the report.

## Production

See `docs/PRODUCTION.md`: hosted backend with Postgres and S3, agent workers, TestFlight distribution, Twilio WhatsApp paging, Meta Wearables Developer Center registration, and the attorney review the consent text needs.
