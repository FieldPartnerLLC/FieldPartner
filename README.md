# FieldPartner

AI partner for field technicians in any trade: HVAC, electrical, plumbing, roofing, cleaning, auto, appliance and general service. The technician wears Meta AI glasses. The phone app (Android or iPhone) streams what they see to an AI partner (Gemini Live) and, when needed, to a human expert who can watch and talk. Every visit is recorded with customer consent, transcribed, and turned into a service report.

This README is the exact path from nothing to a technician using the glasses on a job. Do the parts in order.

| Part | Who | Where | Time |
|---|---|---|---|
| A. Start the server | You (once) | Your Mac, or the live server | 10 min |
| B. Create the company account | Business owner or manager | Expert portal (web) | 2 min |
| C. Invite a technician | Manager | Expert portal > Team | 1 min per tech |
| D. Install the app | Technician | Android or iPhone | 5 min |
| E. Join with the invite code | Technician | App | 1 min |
| F. First-run setup and glasses connection | Technician | App + Meta AI app | 10 min |
| G. First visit | Technician | App + glasses | |
| H. Expert joins a live visit | Expert | Expert portal | |

---

## A. Start the server

Pick one.

**A1. On your Mac (testing, works without a live URL).** One-time setup and keys are in `docs/EMULATOR-AND-STORES.md` sections 0 and 1. Then every time:
```
cd ~/Documents/FieldPartner
./scripts/start-local.sh
```
It prints two addresses. Write them down:
- Server for the phone apps: `http://<Mac IP>:8080`
- Expert portal: `http://<Mac IP>:5173`

Phones must be on the same Wi-Fi as the Mac. Keep the Terminal window open.

**A2. Live server (real use, needed for the app stores).** Follow `docs/HOSTING.md` once. Your addresses become:
- Server for the phone apps: `https://fieldpartner-api.onrender.com`
- Expert portal: `https://fieldpartner-portal.onrender.com`

Everything below is the same for A1 and A2. Only the addresses differ.

## B. Create the company account (business owner)

1. Open the expert portal address in a browser (Chrome or Safari).
2. Click **Create a company account**.
3. Server: the server address from part A (filled in when you open the portal from the Mac).
4. Fill in: company name (for example "Hudson Valley HVAC"), your name, work email, mobile number (expert pages go here), password of 10 or more characters, password again.
5. Click **Create account**. You are signed in as the company admin and land on the Team page.

## C. Invite a technician (manager)

1. Portal > **Team** > **Invite a person**.
2. Name, email, mobile, role **Technician**, tick their trades (HVAC, Electrical, ...).
3. Click **Create invite code**. An 8-letter code appears, for example `K7MQ-3WXP`, with a ready message.
4. Send it: **Open in WhatsApp** (sends the message to their mobile), or **Copy message** and text or email it. If email and Twilio are set up on the server, it is also sent automatically.
5. The code works once and expires in 14 days. The Invites table shows Waiting, Joined, Expired or Cancelled.

Experts (who answer calls from technicians) and other admins are invited the same way with role **Expert** or **Admin**. They open the portal, click **Join your company**, type the code and choose a password. Make sure at least one person (you, as admin, count) has a mobile number on the Team page: expert pages go there.

## D. Install the app (technician)

**Android (Android 12 or newer):**
1. The manager sends `FieldPartner-android.apk` (in this folder; build a fresh one with `./scripts/build-android.sh phone <server address>`).
2. On the phone, open the file (from WhatsApp, Gmail, Google Drive or the Files app).
3. Android asks to allow installs from that app: **Settings** > turn on **Allow from this source** > back > **Install**.
4. If Play Protect warns about an unknown app: **More details** > **Install anyway**. It does this for any app not yet on the Play Store.
5. Once the app is on Google Play, technicians install it from there instead.

**iPhone (iOS 17.2 or newer):** until the app is on the App Store or TestFlight, it is installed from the Mac with Xcode: `docs/EMULATOR-AND-STORES.md` section 5.

**First iPhone and glasses test:** follow `docs/IPHONE-TEST-TONIGHT.md`, a 45-minute script from install to report, with 15 test lines to mark Pass or Fail. After TestFlight is set up, the technician installs TestFlight, opens the invite link, and taps Install.

## E. Join with the invite code (technician)

1. Open **FieldPartner**.
2. Tap **I have an invite code**.
3. Invite code: type the 8 letters. Dash, spaces and lower case are all fine. (The live server address is built into the online APK. Only if the invite message shows a different server, tap **Server settings** and type it.)
4. Tap **Continue**. The app shows your name and email and "<company> invited <your name> as a technician". If the name is wrong, stop and ask the manager.
5. Choose a password (10 or more characters), type it again, tap **Create my account**.
6. Next time, sign in with that email and password. The code is used up.

## F. First-run setup and glasses connection (technician)

Setup starts by itself after the first sign-in. It runs again any time from **Menu** > **Run setup again**, or **Glasses** > **Test video and run setup**.

**Before you start:** charge the glasses and the case. Install the **Meta AI** app (Google Play or App Store) and sign in with your Meta account.

**Step 1 of 4: Allow access.** Tap **Allow access** and accept each prompt: Microphone (required), Nearby devices (Bluetooth, for the glasses), Location (stamps where the customer signed), Notifications (Android), Camera. Each row turns to a green check. Tap **Continue**.

**Step 2 of 4: Choose your camera.** Pick **Meta glasses**. (No glasses yet: pick **Phone camera** and skip to step 4. Test builds also offer **Simulated glasses**, which plays a demo video.) Tap **Continue**.

**Step 3 of 4: Connect your glasses.** Do A to E in order. Each turns into a green check.
- **A. Meta AI app installed and your glasses paired in it.** Tap **Get Meta AI** if it is not installed. Open Meta AI, tap the glasses icon, follow its pairing steps until Meta AI shows your glasses as connected.
- **B. Developer Mode on in Meta AI.** In Meta AI: **Settings** (gear) > **App info** > tap the **version number 5 times** > turn on **Developer Mode**. This is needed until your company registers FieldPartner in the Meta Wearables Developer Center (see `docs/EMULATOR-AND-STORES.md` section 6). After that, skip this step.
- **C. FieldPartner connected to your glasses.** Tap **Connect**. Meta AI opens and asks to allow FieldPartner. Tap **Allow**. You come back to FieldPartner and C turns green.
- **D. Glasses camera allowed.** Tap **Allow camera**. Meta AI opens once more. Choose **Allow always**. Back in FieldPartner, D turns green within 2 seconds.
- **E. Glasses found.** Take the glasses out of the case, unfold them, put them on. The row shows the glasses name and battery.

Tap **Continue to video test**.

**Step 4 of 4: Test the video.** Tap **Start test** and look at something. Within 10 seconds a picture from the glasses appears and "Video received" turns green. Tap **Continue**.
If nothing appears after 20 seconds: glasses unfolded and worn, Bluetooth on, step D allowed, phone close by. Tap **Stop**, then **Start test** again.

**You're ready.** The screen lists the voice commands. Tap **Go to my jobs**.

## G. First visit (technician)

1. The app has three tabs at the bottom: **Jobs** (home), **Glasses** (connection and video source) and **Menu** (profile, wake phrase, language, help, sign out). **Jobs** lists your jobs. No job? Tap **Start a walk-in job** or **+** (trade, title, what the customer says is wrong, customer name, phone and address).
2. Tap **Start** on the job card, or open it and tap **Start visit**. The checklist shows glasses, batteries, storage, server and voice commands. Tap **Start recording**.
3. At the door: tap **Get customer consent**. The customer reads it (English or Spanish), picks Video and audio / Audio only / No recording, types their name and signs with a finger. Tap **Sign and agree**. Video goes to the AI and the expert only after the customer picks Video and audio.
4. Work. The AI hears nothing until you call it:
   - Say **"hey dispatch, what does three flashes mean on this board?"** (your company's wake phrase is shown on screen), or
   - tap **Talk**, or press a Bluetooth clip button, or type in **Ask your partner a question** and tap the send button.
   - **"Partner, stop"** stops it. **"hey dispatch, pause camera"** before a bathroom or bedroom; **"camera on"** after.
   - Tap **Reading** or say "save a reading: CO ambient 4 ppm". Tap **Note** for notes.
   - If the AI sees a hazard, a red **STOP** bar appears and it speaks. Tap the bar to acknowledge.
5. Need a person: say **"hey dispatch, call expert"** or tap **Expert**. The expert is paged (portal, plus WhatsApp or SMS if set up).
6. Done: tap **End** > **End visit**. The AI drafts the report in about 30 seconds. Correct anything, then **Approve and send**. The customer gets it by text and email if those are set up; otherwise the screen shows a link to share.

## H. Expert joins a live visit

1. Expert signs in on the portal (email and password from their invite or from the Team page).
2. **Live** shows every visit in progress; paged visits are at the top.
3. Click **Join live**. You see the glasses video and talk with the technician. The AI goes quiet while you are on. Click **Snapshot** to save a still to the visit. **End help** when done.

---

## What is in this folder

| Folder | What it is | Tested |
|---|---|---|
| `android/` | Android app: Kotlin, Jetpack Compose, Meta Wearables Device Access Toolkit 1.1, LiveKit 2.30. Android 12+. | 57 automated tests, incl. join and setup screen flows, rendered screenshots, and full visits against the real backend |
| `ios/` | iPhone app: SwiftUI, Meta Wearables Device Access Toolkit 1.1, LiveKit. iOS 17.2+. | 19 core logic tests. The full app must be compiled in Xcode (expect a first round of compile fixes) |
| `backend/` | API: accounts, invites, jobs, visits, consent, events, expert paging, reports, uploads. | 49 scenario tests incl. signup, invite and join |
| `agent/` | AI agent worker (LiveKit Agents + Gemini Live). Silent until called, trade safety rules, hazard watcher, mutes for the expert. | 21 tests |
| `portal/` | Expert and admin website: company sign-up, team and invites, live board, join a visit, history, reports. | Type-checked and built |
| `tools/virtual_tech.py` | Plays a recorded glasses video into a real visit and checks the AI's answers. No phone needed. | Runs on your Mac with your keys |
| `render.yaml` | Hosting blueprint: API, agent, Postgres, portal on Render. | |
| `docs/` | `HOSTING.md` (live URL), `EMULATOR-AND-STORES.md` (emulators, devices, Google Play, App Store), `FIELD-TEST-CHECKLIST.md`, `PRODUCTION.md`. | |

## Where things can still fail

- The iPhone app has never been compiled in Xcode. The Android app compiles and its screens are tested on a computer, but not yet on a phone with real glasses.
- The Meta Wearables toolkit officially lists Ray-Ban Meta (Gen 1 and 2), Ray-Ban Meta Display and Oakley Meta HSTN and Vanguard. Other models may not connect.
- Voice wake ("hey dispatch") needs Android 13+. On Android 12 use Talk, the clip button or typing.
- The consent text is a draft. Have an attorney review it before real customers.
