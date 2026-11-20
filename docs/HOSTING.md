# Put FieldPartner online (live URL instead of your Mac's IP)

## Will Vercel work?

Only for the expert portal. Not for the backend or the AI agent.

| Part | Vercel | Why |
|---|---|---|
| Expert portal (website) | Yes | It is a static site. `portal/vercel.json` is ready. |
| Backend API | No | It runs a timer every 30 seconds that keeps paging experts until someone answers, drafts reports in the background after the visit ends, and stores recordings on disk. Vercel functions stop when a request ends (300 seconds max on Hobby, 800 on Pro), have no disk that survives, and have no always-on timer. |
| AI agent | No | It holds a live audio and video connection to LiveKit and Gemini for the whole visit, often 30 to 90 minutes. Vercel functions are capped far below that, and Vercel's long-running workers ("Daemons") are still in beta. |

So the plan is: **backend, agent and database on Render** (one file, `render.yaml`, sets up all of it), portal on Render too (or on Vercel if you prefer).

## What it costs on Render

| Service | Render plan | Per month |
|---|---|---|
| Backend API | Starter (always on, 512 MB) | $7 |
| Disk for recordings | 10 GB | $2.50 |
| AI agent worker | Starter (move to Standard, $25, if the logs show memory restarts) | $7 |
| Postgres database | Basic-256mb | $6 |
| Expert portal | Static site | $0 |
| **Total to start** | | **about $22.50** |

LiveKit Cloud and Gemini are billed separately by usage, the same as when testing on your Mac.

## Keep two LiveKit projects: one for the Mac, one for the live server

Both your Mac and Render run an AI agent with the same name. If they share one LiveKit project, a visit from a phone using the live server can be answered by the agent on your Mac, which then talks to the wrong backend. Create a second LiveKit project called "fieldpartner-prod" and use its keys only on Render. Keep the current project for Mac testing.

## Steps (about 30 minutes)

### 1. Put the code on GitHub (Render deploys from GitHub)

1. Go to https://github.com/new. Name: `fieldpartner`. Choose **Private**. Click Create repository.
2. In Terminal:
   ```
   cd ~/Documents/FieldPartner
   git add -A
   git commit -m "FieldPartner"
   git branch -M main
   git remote add origin https://github.com/<your-github-name>/fieldpartner.git
   git push -u origin main
   ```
   `config.env`, `data/` and `android/local.properties` are in `.gitignore`, so no keys or test data are uploaded. Check with `git status` before pushing if you want to be sure.

### 2. Create the second LiveKit project

1. https://cloud.livekit.io > project menu (top left) > **Create project** > name it `fieldpartner-prod`.
2. Settings > API Keys > **Create key**. Copy the URL (`wss://...`), key and secret somewhere safe for step 3.

### 3. Deploy on Render

1. https://dashboard.render.com > sign up with your GitHub account.
2. **New** > **Blueprint** > connect GitHub > pick the `fieldpartner` repo > **Connect**.
3. Render reads `render.yaml` and lists 4 things: `fieldpartner-db`, `fieldpartner-api`, `fieldpartner-agent`, `fieldpartner-portal`. It asks for the values marked secret. Fill in:
   - `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`: from step 2 (both on the API and the agent).
   - `GOOGLE_API_KEY`: your Gemini key (both on the API and the agent).
   - `PUBLIC_API_URL`: `https://fieldpartner-api.onrender.com` (Render shows the exact address on the service page after the first deploy; fix it there if it differs).
   - `PORTAL_URL` and the portal's `VITE_API_URL`: `https://fieldpartner-portal.onrender.com` and `https://fieldpartner-api.onrender.com`.
   - Twilio values: leave empty for now (paging then shows in the portal only).
4. Click **Apply**. The first build takes 5 to 10 minutes.
5. Check it: open `https://fieldpartner-api.onrender.com/healthz` in a browser. You should see `"ok":true,"db":"postgres","livekit":true,"gemini":true`.
6. Open the agent service > **Logs**. You should see `registered worker {"agent_name": "fieldpartner-agent" ...}`.

### 4. Tell LiveKit where the server is

https://cloud.livekit.io > `fieldpartner-prod` > Settings > **Webhooks** > Add: `https://fieldpartner-api.onrender.com/livekit/webhook`, signing key = the API key from step 2. This lets the server know when an expert joins a call and when cloud recordings finish.

### 5. Create your company on the live server

1. Open `https://fieldpartner-portal.onrender.com` > **Create a company account**.
2. Server: `https://fieldpartner-api.onrender.com`. Company name, your name, email, mobile, password. You are now the company admin.
3. Team > **Invite a person** for each technician. Send them the code.
4. When all your companies exist, set `SIGNUP_ENABLED` to `false` on the API service (Render > fieldpartner-api > Environment) so strangers can't create companies on your server.

### 6. Point the apps at the live server

- **Android:** `./scripts/build-android.sh phone https://fieldpartner-api.onrender.com` makes `FieldPartner-android.apk` with the live address built in. Already-installed apps can also just change it: sign out, edit **Server address** on the sign-in screen.
- **iPhone:** in `ios/Config/Local.xcconfig` set `FP_API_BASE_URL = https:/$()/fieldpartner-api.onrender.com`, then build again in Xcode. Or change the server field on the sign-in screen.
- Turn off plain http for the store builds: Android `app/src/main/res/xml/network_security_config.xml` set `cleartextTrafficPermitted="false"`; iPhone remove the local network exception in `Info.plist`.

## Switching between Mac testing and the live server

Nothing is hard-wired. The server address is a field on the sign-in screen and in Settings.

| Testing on | Start | Server address in the app |
|---|---|---|
| Your Mac (offline from the internet is fine for the API; the AI still needs internet) | `./scripts/start-local.sh` | `http://<Mac IP>:8080` (printed by the script) |
| Live server | nothing to start | `https://fieldpartner-api.onrender.com` |

Accounts are separate: an account created on the Mac does not exist on the live server. Create the company again on the live portal.

## Updating the live server

`git push`. Render rebuilds and redeploys the API, agent and portal automatically (1 to 3 minutes of downtime for the API on the Starter plan while it restarts).

## Portal on Vercel instead (optional)

1. https://vercel.com/new > import the `fieldpartner` repo.
2. Root Directory: `portal`. Framework: Vite.
3. Environment variable `VITE_API_URL` = `https://fieldpartner-api.onrender.com`. Deploy.
4. Set the API's `PORTAL_URL` on Render to the Vercel address so report links point there.

## Backups and limits

- Render backs up paid Postgres daily. Recordings on the disk are not backed up: for more than a pilot, set the `S3_*` variables to an S3 or Cloudflare R2 bucket and the disk is no longer used.
- A service with a disk runs as one instance. That is enough for dozens of technicians. Beyond that, move recordings to S3 and add instances.
