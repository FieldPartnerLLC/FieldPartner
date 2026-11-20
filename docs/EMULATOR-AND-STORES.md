# Test on emulators, then publish to Google Play and the App Store

Everything below runs on your MacBook. Do the sections in order. Each step says exactly what to click or type.

## 0. One-time installs (about 45 minutes, mostly downloads)

1. **Xcode** (iPhone app): App Store app > search "Xcode" > Get. Open it once, accept the license, let it install components. Then in Terminal:
   ```
   sudo xcode-select -s /Applications/Xcode.app
   brew install xcodegen
   ```
2. **Android Studio** (Android app): download from https://developer.android.com/studio > open the .dmg > drag to Applications > open it > Standard setup > Next until it finishes downloading the SDK.
3. **Project setup** (writes the server address into both apps):
   ```
   cd ~/Documents/FieldPartner
   ./scripts/setup-mac.sh
   open -e config.env      # paste LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET, GOOGLE_API_KEY, then save
   ```

## 1. Start the servers (every test session)

```
cd ~/Documents/FieldPartner
./scripts/start-local.sh
```
Leave this window open. If macOS asks whether "node" or "python" may accept incoming connections, click Allow.

## 2. Prove the AI works with no phone at all (2 minutes)

Second Terminal window:
```
cd ~/Documents/FieldPartner
agent/.venv/bin/python tools/virtual_tech.py
```
It plays a recorded "glasses" video of a furnace control board (blinking 3 flashes, a rating plate, a CO meter) into a real visit, asks the AI five questions, checks a tool call, pauses the camera, says stop, ends the visit and waits for the report. You should see `Result: N/N checks passed`. If a required check fails, the line says why. Fix that before testing the apps: the apps use the exact same path.

## 3. Android emulator

1. Android Studio > More Actions > **Virtual Device Manager** > **Create device** > Pixel 8 > Next > system image **API 35, Google Play** (download it) > Next > Finish.
2. Press the play button next to the new device. Wait for the home screen.
3. Turn on the emulator microphone: in the emulator side bar, `...` (Extended controls) > **Microphone** > turn on **Virtual microphone uses host audio input**. (It turns itself off each time the emulator restarts.)
4. Build and install:
   ```
   cd ~/Documents/FieldPartner
   ./scripts/build-android.sh
   ```
   The app opens on the emulator. The first build downloads libraries and takes 5 to 10 minutes.
5. Sign in with the technician login from `data/demo-accounts.txt`. The server address is already filled in.
6. On an emulator the app starts on **Simulated glasses (demo video)**: Meta's own simulated Ray-Ban plays the furnace video through the real glasses toolkit. You can pick your own video in Settings (it must be H.265; convert with `ffmpeg -i in.mp4 -c:v libx265 -tag:v hvc1 -an out.mp4`).
7. Test the visit:
   - Open a job > **Start visit** > the checklist shows green ticks > **Start recording and go to the door**.
   - Header should go from "Connecting" to "Standing by. Say "hey dispatch"". The video box shows the furnace.
   - Type in **"Type a question for your partner"**: `What does the blinking light mean?` > **Ask**. You hear the answer and see the caption.
   - Say out loud near the Mac: "hey dispatch, read me the model number". (Needs step 3. Voice commands need Android 13+, which API 35 is.)
   - **Get customer consent** > sign with the mouse > **Sign and agree**.
   - **Call expert** > on the Mac open the portal address printed by start-local.sh, sign in as the expert, click **Join live**: you see the emulator's glasses video and can talk.
   - **Pause cam**, **Reading**, **Note**, then **End** > the report screen drafts the report > **Approve and send**.
8. Automated Android tests (logic, API, screen flows, and a full visit against your running backend):
   ```
   ./scripts/test-android.sh
   ```

## 4. iPhone Simulator

1. ```
   cd ~/Documents/FieldPartner
   open -e ios/Config/Local.xcconfig     # set DEVELOPMENT_TEAM (Xcode > Settings > Accounts > your team ID) and a unique FP_BUNDLE_ID
   ./scripts/build-ios.sh
   ```
2. Xcode opens. At the top, choose **iPhone 16** (a simulator), press the play button. First build: 5 to 10 minutes.
   If the build fails: open Claude Code in this folder and say "Build the iOS app in ios/ and fix the compile errors." (`CLAUDE.md` tells it how.)
3. The Simulator has no camera, so debug builds automatically pair Meta's simulated glasses playing the same furnace video.
4. Sign in and run the same visit test as step 3.7. Simulator audio uses the Mac microphone.

## 5. Real devices

- **Android phone**: Settings > About phone > tap Build number 7 times > Developer options > USB debugging on. Plug into the Mac, accept the prompt, run `./scripts/build-android.sh`. In app Settings choose **Meta glasses** > **Connect Meta glasses** (Meta AI app opens) > **Allow glasses camera**.
- **iPhone**: pick it at the top of Xcode instead of the simulator, press play. Then Settings > Connect glasses.
- Both: the Meta AI app needs Developer Mode until the app is registered (Meta AI > Settings > App info > tap the version 5 times).
- The phone must be on the same Wi-Fi as the Mac while the servers run on the Mac.

## 6. Before the stores (required)

1. **Host the backend on https.** Stores reject apps that only work on your Wi-Fi, and reviewers must be able to sign in. Follow `docs/PRODUCTION.md` section 1. Then set the address in `android/local.properties` (`fp_api_base_url=https://api.yourdomain.com`) and `ios/Config/Local.xcconfig` (`FP_API_BASE_URL`), and turn off plain http (Android: `res/xml/network_security_config.xml` set `cleartextTrafficPermitted="false"`; iOS: remove the local ATS exception in Info.plist).
2. **Meta Wearables Developer Center** (https://wearables.developer.meta.com): create the app for both platforms, get the App ID and Client Token, put them in `android/local.properties` (`mwdat_application_id`, `mwdat_client_token`) and `ios/Config/Local.xcconfig` (`META_APP_ID`, `CLIENT_TOKEN`). Without this, only phones in Developer Mode can connect glasses. Note: Meta lists some toolkit features as experimental and not yet publishable; this app uses only camera streaming, which is not marked experimental in 1.1.
3. **Privacy policy URL** (both stores require one): say what is recorded, that Google Gemini processes video and speech, retention, and how customers request deletion. Have your attorney review it with the consent text.
4. **Reviewer account**: a technician login on the hosted server plus written steps; tell reviewers glasses are optional (they can use Phone camera or Simulated glasses).

## 7. Google Play ($25 one-time)

1. https://play.google.com/console > create a developer account (identity verification can take a few days).
2. Create the upload key once and keep it safe (losing it means a support ticket):
   ```
   keytool -genkeypair -v -keystore ~/fieldpartner-upload.jks -alias fieldpartner -keyalg RSA -keysize 2048 -validity 10000
   ```
   Add to `android/local.properties`:
   ```
   fp_keystore_path=/Users/prashantchawla/fieldpartner-upload.jks
   fp_keystore_password=...
   fp_key_password=...
   fp_application_id=com.ironrim.fieldpartner
   fp_version_code=1
   fp_version_name=1.0.0
   ```
3. `./scripts/build-android.sh release` > upload `android/app/build/outputs/bundle/release/app-release.aab`.
4. In Play Console: App content (privacy policy, data safety form: audio, video, location, name; shared with Google for AI processing), content rating, target audience 18+, **Foreground service** declaration (microphone, camera and connected device: "keeps the recording and the AI call running while the phone is in the technician's pocket during a service visit"), then **Internal testing** track > add your techs' emails. Internal testing needs no review wait; production review usually takes 1 to 7 days. New personal accounts must run a closed test with 12 testers for 14 days before production; an organization account (IronRim) skips that.
5. Each new upload: raise `fp_version_code` by 1.

## 8. Apple App Store ($99/year)

1. https://developer.apple.com/programs > enroll (as IronRim Corporation needs a D-U-N-S number; allow a few days).
2. App Store Connect > My Apps > + > New App > bundle id = your `FP_BUNDLE_ID`.
3. Xcode: choose **Any iOS Device**, Product > **Archive** > Distribute App > App Store Connect > Upload.
4. TestFlight tab: add internal testers (up to 100, no review). External testers need a short beta review.
5. For the public store: screenshots (6.9" iPhone), description, privacy policy URL, App Privacy answers (same as Play), review notes with the reviewer login and "Meta glasses optional: Settings > Use phone camera". Background audio is declared because the visit records while the phone is locked; say that in the notes. Review usually takes 1 to 3 days.

## What was tested before you received this

| Part | Tests | Where they ran |
|---|---|---|
| Backend | 46 scenario tests | Embedded database; also run on Postgres 16 earlier |
| AI agent | 21 tests | Python |
| Android | 52 tests: command parsing (same cases as iPhone), agent protocol, offline event queue, upload queue, audio conversion, API client with retries, two full screen flows (Robolectric), and one complete visit against the real backend | JVM; debug and release (R8) builds compile |
| iPhone | 19 core logic tests | Swift on Linux. The full app must be compiled in Xcode (step 4) |
| Live AI path | `tools/virtual_tech.py` | Runs on your Mac with your keys (step 2) |

Not tested here, because it needs your keys, an emulator with hardware acceleration, or real glasses: the live LiveKit call, Gemini's answers, the Android and iOS apps running on a device, and Bluetooth glasses. Steps 2 to 5 cover exactly those.
