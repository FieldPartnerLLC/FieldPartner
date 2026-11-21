# FieldPartner Guide

This guide covers the whole FieldPartner project in one place:

- what each part does
- every outside service it uses, with direct links
- how each service was set up
- how to run the project on a Mac
- how the live server is hosted
- how to build and share the apps
- how to keep it all running

Secrets (API keys, passwords, tokens) never go in this file, in GitHub, or in chat. They live in two places only: `config.env` on your Mac, and the Environment page of each Render service.

---

## 1. What FieldPartner is

A technician wears Meta AI glasses on the job. The phone app sends the glasses' video and the technician's voice to an AI partner. A human expert can join a live visit from a web portal when the AI is not enough.

Two AI models work together:

| Model | Job | Why this one |
|---|---|---|
| Google Gemini (Live) | The voice in the technician's ear. Sees the camera, listens, talks back in real time, and watches for hazards. | It has a real-time voice and video mode. |
| Anthropic Claude | The senior expert. For hard questions (error codes, wiring, symptoms the first checks did not explain, or "second opinion"), Gemini says "Checking with the senior expert" and sends the question, the current camera frame and the visit so far to Claude, then speaks Claude's answer. Claude also drafts the service report and writes lessons from each visit. | More careful reasoning, used where accuracy matters most. |

**How it gets smarter every week:**

1. **Trade skills.** There is one file per trade in `shared/skills/<trade>/SKILL.md`, in the Agent Skills format. Each one holds the trade's playbook. Claude reads the skill for the job's trade before it answers. Edit these in plain English; changes go live on the next `git push`.
2. **Lessons.** After a technician approves a visit report, Claude writes the reusable fix: equipment, symptom, error code, cause, fix and parts.
3. **Expert approval.** An expert approves, corrects or rejects each lesson in the portal under **Lessons**. Experts can also add their own. Nothing is used until it is approved.
4. **Used on the next job.** Approved lessons that match the brand, model, error code or symptom go to Claude with the next similar question. Claude says "on past jobs" when it uses one.

Without a Claude key, everything still works: Gemini answers on its own and drafts reports.

### The six parts

| Part | Folder | What it is | Where it runs |
|---|---|---|---|
| Android app | `android/` | The technician's app (Kotlin, Jetpack Compose). Talks to the glasses through the Meta Wearables toolkit. | Technicians' Android phones |
| iPhone app | `ios/` | The same app for iPhone (Swift). Not yet compiled in Xcode. | Technicians' iPhones |
| Backend API | `backend/` | Accounts, companies, invites, jobs, visits, consent, recordings, reports, expert paging (Node.js, Fastify, Postgres). | Render (live) or your Mac (testing) |
| AI agent | `agent/` | Joins every visit as a participant, hears and sees through LiveKit, and answers with Gemini (Python, LiveKit Agents). | Render (live) or your Mac (testing) |
| Expert portal | `portal/` | Website for owners and experts: sign up a company, invite people, watch live visits, review reports (React). | Render (live) or your Mac (testing) |
| Database | managed | Postgres. Holds all company, user, job and visit data. | Render (live). On the Mac, a built-in local database in `data/`. |

### How a visit flows

```
Glasses --Bluetooth--> Phone app --HTTPS--> Backend API (Render) <--> Postgres (Render)
                          |                       ^
                          | live audio + video    | events, reports
                          v                       |
                    LiveKit Cloud room <------> AI agent (Render) <--> Google Gemini
                          ^
                          | expert joins the same room
                    Expert portal (browser)
```

---

## 2. Every site involved

| Site | Direct link | What we use it for | Monthly cost |
|---|---|---|---|
| GitHub | https://github.com/FieldPartnerLLC/FieldPartner | Stores the code. Render deploys from it. | $0 (private repo) |
| Render | https://dashboard.render.com | Hosts the backend API, AI agent, database and portal | About $40.50 (section 6) |
| LiveKit Cloud | https://cloud.livekit.io | Real-time audio and video rooms between phone, AI agent and expert | Usage based. Free tier to start. |
| Google AI Studio (Gemini) | https://aistudio.google.com/apikey | The live AI that sees, listens and talks during visits | Usage based. Billing must be on. |
| Anthropic Console (Claude) | https://console.anthropic.com/settings/keys | The senior expert, report drafting and lessons | Usage based. About $0.01 to $0.05 per question or report. |
| Gmail (App Password) | https://myaccount.google.com/apppasswords | Sends invite emails and expert pages from a Gmail account | $0 |
| Meta Wearables Developer Center | https://wearables.developer.meta.com | Registers the app with Meta so glasses work without Developer Mode (needed before Play Store) | $0 |
| Meta AI app | https://play.google.com/store/apps/details?id=com.facebook.stella | Pairs and manages the glasses on the phone. FieldPartner connects through it. | $0 |
| Twilio (optional) | https://console.twilio.com | WhatsApp and SMS paging to experts. Not set up yet. | Usage based |
| Vercel (optional) | https://vercel.com | Can host the portal instead of Render. Not used now. | $0 |
| Google Play Console (later) | https://play.google.com/console | Publishing the Android app | $25 one time |
| Apple Developer (later) | https://developer.apple.com/account | Publishing the iPhone app and TestFlight | $99 a year |

### Live addresses

| What | Address |
|---|---|
| Backend API | https://fieldpartner-api.onrender.com |
| API health check | https://fieldpartner-api.onrender.com/healthz |
| Expert portal | https://fieldpartner-portal.onrender.com |
| Portal: create a company | https://fieldpartner-portal.onrender.com/signup |
| Portal: Team and invites | https://fieldpartner-portal.onrender.com/team |
| Portal: join with an invite (experts) | https://fieldpartner-portal.onrender.com/join |
| Portal: Lessons (knowledge base review) | https://fieldpartner-portal.onrender.com/lessons |
| LiveKit webhook (set in LiveKit) | https://fieldpartner-api.onrender.com/livekit/webhook |

### Render service pages

| Service | Dashboard | Environment settings | Logs |
|---|---|---|---|
| fieldpartner-api | https://dashboard.render.com/web/srv-dc0bmanf3r2c73b2996g | https://dashboard.render.com/web/srv-dc0bmanf3r2c73b2996g/env | https://dashboard.render.com/web/srv-dc0bmanf3r2c73b2996g/logs |
| fieldpartner-agent | https://dashboard.render.com/worker/srv-dc0bmqff3r2c73b2b79g | https://dashboard.render.com/worker/srv-dc0bmqff3r2c73b2b79g/env | https://dashboard.render.com/worker/srv-dc0bmqff3r2c73b2b79g/logs |
| fieldpartner-portal and fieldpartner-db | https://dashboard.render.com (open them from the service list) | | |
| Blueprint "fieldpartner" | https://dashboard.render.com/blueprints | | |

A healthy `/healthz` looks like this:
`{"ok":true,"db":"postgres","storage":"local","livekit":true,"gemini":true,"claude":true,"twilio":false,"email":true}`

---

## 2b. The single pipeline: develop, test, go live

Everything lives in one repo, and one GitHub Actions pipeline (`.github/workflows/ci.yml`) takes it from a push to production. Nothing needs to be installed on your Mac to test the phone apps; GitHub's machines run the emulators.

| Step | What runs | Where |
|---|---|---|
| 1 | Backend type check and 57 tests | GitHub, Linux |
| 2 | AI agent tests | GitHub, Linux |
| 3 | Portal build | GitHub, Linux |
| 4 | Android unit, API and screen tests, then the APK build (live server built in) | GitHub, Linux |
| 5 | The APK installed and opened on the latest Pixel emulator (Android 15). It fails on a crash or if sign-in does not appear | GitHub, Linux with KVM |
| 6 | iPhone core tests, Xcode build, unit tests, then the app installed and opened on the newest iPhone simulator | GitHub, macOS (latest Xcode) |
| 7 | On main, when 1 to 6 pass: the APK published as the **latest** release, at https://github.com/FieldPartnerLLC/FieldPartner/releases/tag/latest | GitHub |
| 8 | Render deploys the API, agent and portal, but only after all checks pass (`autoDeployTrigger: checksPass` in `render.yaml`) | Render |
| 9 | Live check: waits until Render runs the new commit, then checks health and the portal. It also runs every hour (`.github/workflows/live-check.yml`) | GitHub |

Screenshots from the emulator and simulator are saved on each run under Actions > the run > Artifacts.

To ship a change: `make ship m="what changed"`, then watch https://github.com/FieldPartnerLLC/FieldPartner/actions. Run `make` to see every command.

Costs: GitHub gives private organization repos 2,000 Actions minutes a month for free. macOS minutes count 10 times. One full run uses about 15 Linux minutes plus about 20 macOS minutes (200 counted), so about 9 full runs a month fit in the free tier. Beyond that, GitHub bills per minute: https://github.com/pricing/calculator.

## 3. One-time setup on your Mac (for local testing)

### Tools to install

Open Terminal and run:

```
xcode-select --install          # Apple command-line tools, includes git
brew install node python@3.12 xcodegen
```

Don't have Homebrew yet? Install it first from https://brew.sh.

You also need these:

- **Android Studio**, to build the Android app on the Mac and run the emulator: https://developer.android.com/studio. The APKs so far were built for you, so it's only needed when you want to build yourself.
- **Xcode**, for the iPhone app: from the Mac App Store.

### Get the code

The project is already at `~/Documents/FieldPartner`. On a new Mac:

```
cd ~/Documents
git clone https://github.com/FieldPartnerLLC/FieldPartner.git
cd FieldPartner
```

### Fill in the keys

```
./scripts/setup-mac.sh       # installs packages, creates config.env, writes android/local.properties
open -e config.env           # fill in the 4 values below, save, close
```

| Value in `config.env` | Where to get it |
|---|---|
| `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` | https://cloud.livekit.io > project **MetaAISmartGlasses** (the Mac test project) > Settings > API Keys |
| `GOOGLE_API_KEY` | https://aistudio.google.com/apikey > Create API key. Billing must be on for the project. |
| `ANTHROPIC_API_KEY` (optional) | https://console.anthropic.com/settings/keys > Create Key. Add a line `ANTHROPIC_API_KEY=` if your `config.env` doesn't have one. |

`setup-mac.sh` fills in `JWT_SECRET`, `AGENT_SERVICE_TOKEN`, `PUBLIC_API_URL` and `PORTAL_URL` automatically. `config.env` is listed in `.gitignore`, so it never goes to GitHub.

---

## 4. Run everything on the Mac (offline testing)

```
cd ~/Documents/FieldPartner
./scripts/start-local.sh
```

It starts the backend on port 8080, the AI agent and the portal on port 5173, then prints:

- **Server for the phone apps:** `http://<Mac IP>:8080`
- **Expert portal:** `http://<Mac IP>:5173`
- **Demo logins:** saved in `data/demo-accounts.txt` (`owner@`, `expert@` and `tech@fieldpartner.local`, all with the same password)
- **Logs:** in `logs/` (`backend.log`, `agent.log`, `portal.log`)

Phones must be on the same Wi-Fi as the Mac. Press `Ctrl+C` to stop.

To check a full visit end to end without a phone, run this in a second Terminal window:

```
agent/.venv/bin/python tools/virtual_tech.py
```

Accounts on the Mac and accounts on the live server are separate. A company made on one does not exist on the other.

---

## 5. Commands you will use

| Command | What it does |
|---|---|
| `./scripts/setup-mac.sh` | First-time setup. Safe to run again. |
| `./scripts/start-local.sh` | Runs backend, agent and portal on the Mac |
| `./scripts/build-android.sh` | Debug build, installs on the emulator or a USB-connected phone |
| `./scripts/build-android.sh phone https://fieldpartner-api.onrender.com` | Makes `FieldPartner-android-online.apk` (live server built in) |
| `./scripts/build-android.sh phone` | Makes `FieldPartner-android-offline.apk` (your Mac's address built in) |
| `./scripts/build-android.sh release` | Signed Play Store bundle (`.aab`). Needs a keystore: `docs/EMULATOR-AND-STORES.md` |
| `./scripts/test-android.sh` | All Android tests (logic, API, screen flows) |
| `./scripts/build-ios.sh` | Generates and opens the Xcode project |
| `cd backend && npm test` | Backend tests |
| `cd agent && .venv/bin/python -m pytest -q` | AI agent tests |
| `curl https://fieldpartner-api.onrender.com/healthz` | Checks the live server |
| `make` | Lists the one-line commands: `make dev`, `make test`, `make apk`, `make ios`, `make live`, `make ship m="..."` |

### Saving changes to GitHub (this also updates the live server)

```
cd ~/Documents/FieldPartner
git add -A
git commit -m "describe the change"
git push
```

GitHub Actions tests everything (section 2b). When it passes, Render redeploys the API, agent and portal, usually 15 to 25 minutes after the push. If a check fails, nothing is deployed and the live system stays as it was.

---

## 6. How the live server was set up

This is the exact order it was done in, so it can be repeated for a second environment or after a rebuild.

### 6.1 GitHub

**Purpose:** holds the code; Render pulls from it on every push.

1. The repository is https://github.com/FieldPartnerLLC/FieldPartner (private, under the FieldPartnerLLC organization).
2. The first push from the Mac:
   ```
   cd ~/Documents/FieldPartner
   git add -A
   git commit -m "FieldPartner full build"
   git branch -M main
   git remote add origin https://github.com/FieldPartnerLLC/FieldPartner.git
   git push -u origin main
   ```
3. When git asks for a password, use a personal access token, not your GitHub password:
   - Go to https://github.com/settings/tokens > **Generate new token (classic)**.
   - Tick `repo`, then authorize the token for FieldPartnerLLC.
4. These are never uploaded, because of `.gitignore`: `config.env`, `data/`, `logs/`, `android/local.properties`, `*.apk`, `*.aab` and keystores.

### 6.2 LiveKit Cloud

**Purpose:** carries live audio and video between the phone, the AI agent and the expert.

There are **two LiveKit projects**, on purpose:

| Project | Used by | Why separate |
|---|---|---|
| MetaAISmartGlasses | Your Mac (`config.env`) | Testing |
| FieldPartner (wss://fieldpartner-qeavdxki.livekit.cloud) | Render (API and agent) | Live use |

Both the Mac and Render run an agent with the same name. If they shared one project, a live visit could be picked up by the agent on your Mac.

How the live project was made:

1. https://cloud.livekit.io > click the project name at the top left > **Create project**.
2. Open the new project > **Manage API keys** (Settings > API Keys) > **Create key**. Save the URL, key and secret in a notes file, not in chat.
3. Add the webhook. In the project, go to **Settings > Webhooks > Add**:
   - Name: `FieldPartner`
   - URL: `https://fieldpartner-api.onrender.com/livekit/webhook`
   - Signing API key: the same key used on Render

   This tells the server when an expert joins and when cloud recordings finish.

Ignore LiveKit's **Deploy your first agent** button. The agent runs on Render, so "Agents deployed" stays at 0 even when everything works.

### 6.3 Google Gemini

**Purpose:** the AI model. It answers questions, spots hazards, and drafts reports.

1. https://aistudio.google.com/apikey > **Create API key**.
2. Turn on billing for that Google Cloud project, or the key gets rate limited.
3. The same key is used on the Mac (`config.env`) and on Render (`GOOGLE_API_KEY` on both the API and the agent).

### 6.3b Anthropic Claude

**Purpose:** the senior expert during visits, report drafting, and lessons.

1. Go to https://console.anthropic.com and sign up. Add billing under Settings > Billing.
2. Go to https://console.anthropic.com/settings/keys > **Create Key**, named `FieldPartner`.
3. Paste it on Render: **fieldpartner-api** > Environment > `ANTHROPIC_API_KEY` > **Save, rebuild, and deploy**.
   It goes on the API only. The agent reaches Claude through the API, so the key lives in one place.
4. `/healthz` then shows `"claude":true`.
5. Model: `CLAUDE_MODEL` (default `claude-sonnet-5-5`), plus `CLAUDE_REPORT_MODEL` for reports and lessons. Change them on the same page.

### 6.4 Render

**Purpose:** hosts everything that must be online all the time.

**Why not Vercel:** Vercel stops code when a request ends (5 minutes maximum on the free plan, about 13 on Pro) and has no permanent disk. The backend needs an always-on timer for paging and a disk for recordings. The AI agent holds a call open for the whole visit, often 30 to 90 minutes. Vercel could host only the portal.

**What gets created.** One file, `render.yaml` in the repo (a "Blueprint"), defines all four parts:

| Service | Type | Plan | Monthly | Purpose |
|---|---|---|---|---|
| fieldpartner-api | Web service (Docker, `backend/Dockerfile`) | Starter, 512 MB | $7 | The API the apps and portal call |
| Recordings disk | 10 GB at `/data` on the API | | $2.50 | Stores visit audio and video |
| fieldpartner-agent | Background worker (Docker, `agent/Dockerfile`) | Standard, 2 GB | $25 | The AI partner in every visit |
| fieldpartner-db | Postgres | Basic-256mb | $6 | All data |
| fieldpartner-portal | Static site (`portal/`) | | $0 | Expert and owner website |
| Workspace | Hobby | | $0 | Keep it on Hobby; Pro is not needed |
| **Total** | | | **about $40.50** | Plus LiveKit and Gemini usage |

**Why the agent is on Standard:** on Starter (512 MB) it crashed with "Ran out of memory" every 30 seconds. Measured: about 210 MB idle plus about 430 MB per live visit. It now runs visits as threads in one process (`AGENT_EXECUTOR=thread`, `AGENT_IDLE_PROCESSES=1` in `render.yaml`). On 2 GB that handles about 3 visits at the same moment.

**Steps that were done:**

1. https://dashboard.render.com: sign up with GitHub.
2. Add a card: **My Workspace** > **Settings** > **Billing**. Without a card, the Blueprint shows "a few issues" and won't deploy.
3. **+ New** > **Blueprint** > **Connect GitHub**:
   - When GitHub asks where to install Render, pick **FieldPartnerLLC** and allow the `FieldPartner` repo.
   - If the repo is missing, add it here: https://github.com/organizations/FieldPartnerLLC/settings/installations > Render > Configure.
4. Select `FieldPartnerLLC/FieldPartner`. Use Blueprint name `fieldpartner` and branch `main`.
5. Fill in the secret values Render asks for:

   | Service | Key | Value |
   |---|---|---|
   | api and agent | `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` | From the FieldPartner LiveKit project |
   | api and agent | `GOOGLE_API_KEY` | Gemini key |
   | api | `ANTHROPIC_API_KEY` | Claude key (section 6.3b) |
   | api | `PUBLIC_API_URL` | `https://fieldpartner-api.onrender.com` |
   | api | `PORTAL_URL` | `https://fieldpartner-portal.onrender.com` |
   | api | `TWILIO_*` (4 values) | Leave empty for now |
   | portal | `VITE_API_URL` | `https://fieldpartner-api.onrender.com` |

   Render generates these by itself: `JWT_SECRET`, `AGENT_SERVICE_TOKEN`, `DATABASE_URL` and `DATA_DIR`. The agent finds the API over Render's private network (`BACKEND_URL`), so that traffic never crosses the internet.
6. **Deploy Blueprint**. The first build takes 5 to 10 minutes.
7. Check that it's up:
   - The health check (https://fieldpartner-api.onrender.com/healthz) shows `"ok":true`.
   - The agent logs show `registered worker` with `agent_name: fieldpartner-agent`.

### 6.5 Email for invites (Gmail)

**Purpose:** sends invite codes and expert pages by email.

1. Use a Gmail account, not `pc@bluehorizonestates.com`, so the company domain is never flagged for bulk mail.
2. Turn on 2-Step Verification at https://myaccount.google.com/security.
3. Create an App Password at https://myaccount.google.com/apppasswords, named `FieldPartner`. Copy the 16 characters.
4. Add these on fieldpartner-api > Environment (https://dashboard.render.com/web/srv-dc0bmanf3r2c73b2996g/env), then click **Save, rebuild, and deploy**:

   | Key | Value |
   |---|---|
   | `SMTP_HOST` | `smtp.gmail.com` (watch the spelling: SMTP, not SMPT) |
   | `SMTP_PORT` | `587` |
   | `SMTP_USER` | the Gmail address |
   | `SMTP_PASS` | the App Password, no spaces |
   | `SMTP_FROM` | `FieldPartner <the Gmail address>` |

5. `/healthz` then shows `"email":true`. Gmail sends up to about 500 emails a day. If invites land in spam as volume grows, switch to Resend or SendGrid; it's the same five settings with different values.

### 6.6 WhatsApp and SMS paging (optional, not set up)

1. Create a Twilio account at https://console.twilio.com.
2. Buy a number, and enable the WhatsApp sender.
3. On fieldpartner-api > Environment, set these four values: `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_SMS_FROM`, `TWILIO_WHATSAPP_FROM`.
4. `/healthz` then shows `"twilio":true`.

Until then, expert pages show in the portal and go by email.

### 6.7 Portal on Vercel instead (optional)

1. https://vercel.com/new > import `FieldPartnerLLC/FieldPartner`.
2. Root Directory `portal`, Framework Vite, environment variable `VITE_API_URL` = `https://fieldpartner-api.onrender.com`.
3. On Render, change the API's `PORTAL_URL` to the Vercel address.

---

## 7. First company, invites and technicians (live)

1. Open the signup page (https://fieldpartner-portal.onrender.com/signup) and choose **Create a company account**. You become the company admin.
2. On the Team page (https://fieldpartner-portal.onrender.com/team), click **Invite a person**:
   - Enter the name and email, and choose the role (technician, expert or admin).
   - The invite email goes out automatically. The page also offers **Copy message** and **Open in WhatsApp**.
   - Codes are good for 14 days (`INVITE_DAYS`). Re-inviting the same email replaces the old code.
3. The technician installs the app, taps **I have an invite code**, types the code and sets a password. The app then walks them through setup:
   - permissions
   - choosing glasses or the phone camera
   - connecting the Meta glasses through the Meta AI app (Developer Mode on)
   - a live video test
4. Experts accept their invite on the portal's join page: https://fieldpartner-portal.onrender.com/join
5. Once all your companies exist, set `SIGNUP_ENABLED=false` on fieldpartner-api so strangers can't create companies on your server.

The full step-by-step for technicians is in `README.md`, Parts E to H.

---

## 8. The apps

### Android

There are two builds, both in the FieldPartner folder:

| File | Server built in | Use |
|---|---|---|
| `FieldPartner-android-online.apk` | `https://fieldpartner-api.onrender.com` | Anyone, anywhere |
| `FieldPartner-android-offline.apk` | Your Mac's Wi-Fi address | Testing against `start-local.sh` on the same Wi-Fi |

Current version: **1.2.0** (version code 3). It installs over older versions and keeps the sign-in. When the senior expert answers, the answer also shows on the phone screen.

- **Installing on a phone:** send the APK by Google Drive, email or USB, open it on the phone, and allow installs from that app when Android asks.
- **Sharing with many testers:** upload the APK to Google Drive, set sharing to **Anyone with the link**, and send the link. For more than a handful of testers, use Firebase App Distribution or Play Store internal testing.
- **Changing the server without a new build:** sign out, tap **Server settings** on the sign-in screen, and type the address.

App layout:
- three tabs: **Jobs** (home), **Glasses** (connection and video source) and **Menu** (profile, wake phrase, language, help, sign out)
- one design system in `android/app/src/main/java/com/fieldpartner/app/ui/Theme.kt` and `Components.kt`: one blue, gray canvas, white cards, one font family at fixed sizes, no gradients

### iPhone

```
./scripts/build-ios.sh
```

Before you run it, set your Team ID and bundle ID in `ios/Config/Local.xcconfig`. In Xcode, pick your iPhone and press Run. The iPhone app has not been compiled in Xcode yet, so expect a first round of fixes. TestFlight and App Store steps are in `docs/EMULATOR-AND-STORES.md`.

### Store publishing (later)

| Store | Steps | Before submitting |
|---|---|---|
| Google Play | `docs/EMULATOR-AND-STORES.md`: keystore, `build-android.sh release`, upload the `.aab` | Register the app in the Meta Wearables Developer Center (https://wearables.developer.meta.com), so technicians don't need Developer Mode |
| Apple | `docs/EMULATOR-AND-STORES.md`: TestFlight, then App Store | Same Meta registration |

Also before submitting, turn off plain http:
- Android: set `cleartextTrafficPermitted="false"` in `network_security_config.xml`.
- iPhone: remove the local network exception in `Info.plist`.

---

## 9. Keeping it running

| Task | How |
|---|---|
| Update the live server | `git push`. Render redeploys in 3 to 10 minutes. |
| Check it is up | Open the health check: https://fieldpartner-api.onrender.com/healthz |
| See errors | Render > service > **Logs** (links in section 2) |
| See crashes or restarts | Render > service > **Events** |
| Change a setting or key | Render > service > **Environment** > Edit > **Save, rebuild, and deploy** |
| Roll back a bad deploy | Render > service > **Events** > **Rollback** next to the last good deploy |
| Database backups | Render backs up paid Postgres daily |
| Recordings backup | Not backed up on the Render disk. Before real customer volume, set `S3_BUCKET`, `S3_REGION`, `S3_ACCESS_KEY_ID` and `S3_SECRET_ACCESS_KEY` (AWS S3 or Cloudflare R2). The disk is then no longer used. |
| Rotate a leaked key | Make a new key at the provider, paste it on Render (both API and agent where it appears), then delete the old one |

## 9b. AI cost control

Every AI call is metered. Admins see the month's spend at **Portal > AI costs** (https://fieldpartner-portal.onrender.com/costs): total, average per visit, by job and by model.

### Who does what (cheapest model that can do the job)

| Job | Model | Price per 1M tokens (in / out) |
|---|---|---|
| Voice and camera during the visit | Gemini 3.1 Flash Live | $3 audio, $1 video, $0.75 text in / $12 audio out |
| Hazard watcher (one frame every 5 s) | Gemini 3.5 Flash-Lite | $0.30 / $2.50 |
| Routine questions | Claude Haiku 5.5 | $0.10 / $0.50 |
| Reading nameplates and display codes | Claude Haiku 5.5 | $0.10 / $0.50 |
| Routine reports, lesson writing | Claude Haiku 5.5 | $0.10 / $0.50 |
| Safety questions (gas, CO, sparks, high voltage, refrigerant...), a fix that already failed, or Haiku not confident | Claude Sonnet 5.5 | $2 / $10 |
| Reports for visits with a safety stop, supervisor, dispute or a long transcript | Claude Sonnet 5.5 | $2 / $10 |

The rules live in `backend/src/lib/router.ts`. For a routine question Haiku answers and grades itself; if it says it is not confident, the question goes to Sonnet.

### Gemini Live settings (the biggest cost)

Gemini Live charges for the whole conversation again on every turn, so the agent keeps that conversation short:

- The assistant hears and sees nothing until the wake phrase or the Talk button (already built in).
- Camera at low detail (`GEMINI_MEDIA_RESOLUTION=low`) and 1 frame every 2 seconds (`GEMINI_VIDEO_FPS=0.5`), only while listening. Small print goes to `read_label` (one sharp photo to Haiku) instead.
- Session memory is trimmed to 5,000 tokens whenever it passes 12,000 (`GEMINI_CONTEXT_TRIGGER_TOKENS`, `GEMINI_CONTEXT_TARGET_TOKENS`). Readings, equipment and notes are saved by tools, so nothing important is lost.
- The hazard watcher skips frames when the view has not changed and rechecks a still view every 30 s (`SAFETY_STATIC_RECHECK_SECONDS`).

### Budget

`AI_MONTHLY_BUDGET_USD` on the API service (default 100, per company per calendar month). Past it, every Claude call uses Haiku. The live voice keeps working.

### Expected cost (estimate, check the AI costs page after real visits)

A 30-minute visit with the assistant listening for about 8 minutes over 25 questions: about **$0.45** (Gemini Live about $0.40, watcher about $0.04, Claude about $0.02). Before these controls the same visit was about $2. 10 technicians doing 4 visits a day for 22 days is about **$400 a month** in AI. Render hosting is a separate $40.50 a month.

---

## 10. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Render Blueprint says "a few issues" | No card on the workspace. Add one under Billing, then retry. |
| Agent restarts every 30 seconds | Out of memory. It must be on the Standard plan with `AGENT_EXECUTOR=thread` (already in `render.yaml`). |
| `/healthz` shows `"email":false` | `SMTP_HOST` is missing or misspelled on fieldpartner-api. |
| `/healthz` shows `"livekit":false`, `"gemini":false` or `"claude":false` | The key is missing or wrong on fieldpartner-api. |
| AI says "senior expert not reachable" | `ANTHROPIC_API_KEY` missing or out of credit. Check fieldpartner-api logs for "consult failed". Gemini keeps answering on its own. |
| No lessons appear under Lessons | Lessons are proposed only after a report is approved, and only when there was a real fault to learn from. Needs `ANTHROPIC_API_KEY`. |
| App says it can't reach the server | Live build: check `/healthz`. Offline build: the phone must be on the same Wi-Fi as the Mac and `start-local.sh` must be running. |
| Visit starts but the AI never answers | Agent logs should show `registered worker`. The LiveKit keys on the API and the agent must be from the same project. |
| A live visit was answered by the Mac's agent | The Mac and Render are using the same LiveKit project. Keep the Mac on MetaAISmartGlasses. |
| Glasses not found | In Meta AI: glasses paired, Developer Mode on, FieldPartner allowed, camera allowed. The glasses must be unfolded and worn, with Bluetooth on. |
| Invite email in spam | Mark it "Not spam" once. If it keeps happening, move to Resend or SendGrid. |
| Mac IP changed and phones can't connect | Run `./scripts/start-local.sh` again; it updates the address. Build a new offline APK if needed. |

---

## 11. Other documents in the repo

| File | What it covers |
|---|---|
| `README.md` | Step-by-step from creating an account to the first visit (Parts A to H) |
| `docs/HOSTING.md` | Short hosting steps and costs |
| `docs/EMULATOR-AND-STORES.md` | Emulators, simulators, signing, Play Store, TestFlight, App Store, Meta registration |
| `docs/FIELD-TEST-CHECKLIST.md` | What to check on a real job |
| `docs/PRODUCTION.md` | Production hardening notes |
| `CLAUDE.md` | Notes for working on the code with Claude Code |
| `render.yaml` | The Render Blueprint (all live services) |
| `shared/skills/` | Trade skills (one `SKILL.md` per trade) that Claude reads |
| `config.env.example` | Template for the Mac's `config.env` |
