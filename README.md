# FieldPartner

AI partner for field technicians in any trade: HVAC, electrical, plumbing, roofing, cleaning, auto, appliance and general service. The technician wears Meta AI glasses; the phone app (Android or iPhone) streams what they see to an AI partner (Gemini Live) and, on request, to a human expert who can watch and talk. Every visit is recorded with customer consent, transcribed, and turned into a service report.

## What's in this folder

| Folder | What it is | Tested |
|---|---|---|
| `android/` | Android app (Kotlin, Jetpack Compose, Meta Wearables Device Access Toolkit 1.1, LiveKit 2.30). Android 12+. | 52 automated tests incl. screen flows and a full visit against the real backend; debug and release builds compile |
| `ios/` | iPhone app (SwiftUI, Meta Wearables Device Access Toolkit 1.1, LiveKit). iOS 17.2+. | Core logic: 19 tests. Full app: compile in Xcode on the Mac |
| `backend/` | API server (Node/TypeScript). Jobs, visits, consent, events, escalation paging, reports, uploads, LiveKit tokens. Embedded database on a laptop, Postgres in production. | 46 scenario tests |
| `agent/` | AI agent worker (Python, LiveKit Agents, Gemini Live). Silent until called, trade safety rules, background hazard watcher, mutes when an expert joins. | 21 tests |
| `portal/` | Expert web portal (React). Live board, join a visit with video and two-way audio, snapshots, history, review queue, team admin. | Type-checked and built |
| `tools/virtual_tech.py` | A virtual technician: plays a recorded glasses video into a real visit and checks the AI's answers. No phone needed. | Runs on your Mac with your keys |
| `shared/trades.json` | Trade profiles: required readings, stop rules, never-guide steps, hazards, verification. | Used by tests |
| `scripts/` | Setup, start, build and test commands for the Mac. | |
| `docs/` | Emulator and store steps, field test checklist, production deployment. | |

Both apps speak the same protocol to the same backend, agent and portal, so a company can mix Android and iPhone technicians.

## Start here

**`docs/EMULATOR-AND-STORES.md`** has the exact steps, in order:

1. One-time installs (Xcode, Android Studio) and `./scripts/setup-mac.sh`
2. `./scripts/start-local.sh` to run the servers
3. `agent/.venv/bin/python tools/virtual_tech.py` to prove the AI path works with no phone
4. Android emulator: `./scripts/build-android.sh`
5. iPhone Simulator: `./scripts/build-ios.sh`, then press play in Xcode
6. Real phones and glasses
7. Google Play and App Store submission

No glasses yet? Both apps have simulated glasses (Meta's MockDeviceKit playing a demo video) and a phone camera mode.

## How a visit works

1. Tech opens the job, runs the pre-visit checks (glasses, batteries, storage, server, voice).
2. Recording starts at the door. The customer reads and signs consent on the phone: video and audio, audio only, or no recording. Video to the AI and the expert stays off until consent says video.
3. The AI hears nothing until called: "hey dispatch, what does three flashes mean?" or the Talk button, a Bluetooth clip button, or typing. It sees the glasses video while answering, saves readings and equipment details, and warns about hazards (a background watcher checks a frame every 5 seconds).
4. "Hey dispatch, call expert" pages the expert by WhatsApp, then SMS. The expert joins from the portal, sees the video and talks; the AI goes quiet.
5. End visit: recordings upload (video waits for Wi-Fi), the AI drafts the report, the tech corrects and approves, the customer gets it by text and email.

## Production

`docs/PRODUCTION.md`: hosted backend with Postgres and S3, agent workers, Twilio WhatsApp paging, Meta Wearables Developer Center registration, and the attorney review the consent text needs.
