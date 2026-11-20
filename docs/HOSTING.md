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
| AI agent worker | Standard (2 GB). Starter's 512 MB is not enough: the agent uses about 210 MB idle plus about 430 MB per live visit | $25 |
| Postgres database | Basic-256mb | $6 |
| Expert portal | Static site | $0 |
| **Total to start** | | **about $40.50** |

LiveKit Cloud and Gemini are billed separately by usage, the same as when testing on your Mac.

## Keep two LiveKit projects: one for the Mac, one for the live server

Both your Mac and Render run an AI agent with the same name. If they share one LiveKit project, a visit from a phone using the live server can be answered by the agent on your Mac, which then talks to the wrong backend. Create a second LiveKit project called "fieldpartner-prod" and use its keys only on Render. Keep the current project for Mac testing.

## Steps (about 30 minutes)

### 1. Put the code on GitHub (Render deploys from GitHub)

The code lives at https://github.com/FieldPartnerLLC/FieldPartner (private). To push new changes:
```
cd ~/Documents/FieldPartner
git add -A
git commit -m "describe the change"
git push
```
`config.env`, `data/`, `android/local.properties` and APK files are in `.gitignore`, so no keys, test data or builds are uploaded.

### 2. Create the second LiveKit project

1. https://cloud.livekit.io > project menu (top left) > **Create project** > name it `fieldpartner-prod`.
2. Settings > API Keys > **Create key**. Copy the URL (`wss://...`), key and secret somewhere safe for step 3.

### 3. Deploy on Render

1. https://dashboard.render.com > sign up with your GitHub account.
2. **New** > **Blueprint** > **Connect GitHub**. When GitHub asks where to install Render, pick the **FieldPartnerLLC** organization (not your personal account) and allow the `FieldPartner` repo. Back in Render, pick `FieldPartnerLLC/FieldPartner` > **Connect**. If the repo is not listed, go to https://github.com/organizations/FieldPartnerLLC/settings/installations > Render > Configure and add the repo.
3. Render reads `render.yaml` and lists 4 things: `fieldpartner-db`, `fieldpartner-api`, `fieldpartner-agent`, `fieldpartner-portal`. It asks for the values marked secret. Fill in:
   - `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`: from step 2 (both on the API and the agent).
   - `GOOGLE_API_KEY`: your Gemini key (both on the API and the agent).
   - `PUBLIC_API_URL`: `https://fieldpartner-api.onrender.com`. Render may add a suffix if that name is taken (for example `fieldpartner-api-x7k2.onrender.com`). After the first deploy, copy the real address from the top of the fieldpartner-api page and fix `PUBLIC_API_URL` and the portal's `VITE_API_URL` if they differ, then redeploy the portal (Manual Deploy > Deploy latest commit).
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

- **Android:** `./scripts/build-android.sh phone https://fieldpartner-api.onrender.com` makes `FieldPartner-android-online.apk` with the live address built in. Already-installed apps can also just change it: sign out, edit **Server address** on the sign-in screen.
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
- A service with a disk runs as one instance. That is enough for dozens of technicians. The agent on Standard handles about 3 visits at the same moment; for more, raise its plan or add instances. Beyond that, move recordings to S3 and add instances.
