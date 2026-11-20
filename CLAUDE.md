# Notes for Claude Code working in this repo

FieldPartner: Android app + iOS app + backend + AI agent + expert portal. Read README.md first.

## Building the iOS app (macOS + Xcode)

The iOS app was written without access to Xcode, so expect a first round of compile errors.
Fix them without changing behavior:

1. `cd ios && xcodegen generate` (install with `brew install xcodegen` if missing).
2. Build: `xcodebuild -project FieldPartner.xcodeproj -scheme FieldPartner -destination 'generic/platform=iOS' -allowProvisioningUpdates build 2>&1 | grep -E "error|warning: unre" | head -50`
3. Fix errors one file at a time. Reference implementations for the APIs used:
   - Meta Wearables DAT 1.1: https://github.com/facebook/meta-wearables-dat-ios (samples/CameraAccess shows DeviceSession, addCamera, Stream, MockDeviceKit). API docs: https://wearables.developer.meta.com/llms.txt
   - LiveKit Swift 2.19: BufferCapturer, LocalVideoTrack.createBufferTrack, sendText(_:for:), registerTextStreamHandler, AudioManager.shared.add(localAudioRenderer:).
4. Run unit tests: `xcodebuild test -project FieldPartner.xcodeproj -scheme FieldPartner -destination 'platform=iOS Simulator,name=iPhone 16'`
5. Signing values live in `ios/Config/Local.xcconfig` (not committed).

Do not remove: the wake-word gating (AI hears nothing until called), consent gating of video,
pause-camera privacy controls, the offline EventQueue, or the UploadManager's Wi-Fi rule.

## Building the Android app

`android/` is a standard Gradle project (AGP 8.11, Kotlin 2.2, Compose, minSdk 31). It builds and its tests pass.

- Build and install on the emulator: `./scripts/build-android.sh`. Tests: `./scripts/test-android.sh`.
- Java: use Android Studio's bundled JDK (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`) if `java -version` is older than 17.
- Meta DAT Android 1.1 and LiveKit Android 2.30 come from Maven Central (no tokens). DAT API reference: https://github.com/facebook/meta-wearables-dat-android (AGENTS.md, samples/CameraAccess).
- Pure logic lives in `app/src/main/java/com/fieldpartner/app/core/` and is unit tested on the JVM. Keep it free of Android imports.
- Mic: during a call WebRTC owns the microphone; `audio/MicBus` taps its samples (JavaAudioDeviceModule SamplesReadyCallback) for the transcript and the recorder. Do not open a second AudioRecord while connected.
- Transcript: `audio/TranscriptEngine` pipes those samples into SpeechRecognizer with EXTRA_AUDIO_SOURCE (Android 13+). Android 12 falls back to Talk button and typed questions.

## Tests elsewhere

- Backend: `cd backend && npm test` (embedded DB). Against Postgres: `TEST_DATABASE_URL=postgres://... npm test`.
- Agent: `cd agent && ./.venv/bin/python -m pytest -q`.
- iOS core logic without Xcode: `cd ios && swift test`.
- Portal: `cd portal && npx tsc -p tsconfig.json && npx vite build`.
- Live system end to end (needs keys and start-local.sh running): `agent/.venv/bin/python tools/virtual_tech.py`.

## Protocol between phone and agent (LiveKit text streams)

- Phone -> agent, topic `fp.cmd`: `{"type":"ask","text":...}`, `listen`, `stop`, `continue`, `hint_play`, `camera` (`allowed`), `disputed`, `context_refresh`, `end`.
- Agent -> phone, topic `fp.event`: `assistant_state` (`idle|listening|thinking|speaking|muted`), `tool` (`save_reading|save_equipment|add_note|draft_estimate`), `hint`, `safety`, `escalation`, `expert`, `watcher`.
- Captions: LiveKit's `lk.transcription` topic.
