---
name: ios-ci-loop
description: Use when the iOS app fails to compile, fails its tests or crashes on launch and you have no Mac, so GitHub's macOS runners become the Xcode build loop.
---

# Fix iOS build and launch failures through CI

The CI job "iPhone build and tests (latest iPhone simulator)" runs, in order:
1. `swift test` in `ios/` (core logic only, see `ios/Package.swift`).
2. `xcodegen generate` with a CI `Config/Local.xcconfig` (empty DEVELOPMENT_TEAM, META_APP_ID 0).
3. `scripts/ci/ios-smoke.sh`: build, unit test, install and open on the newest iPhone simulator, fail if the app is not running after 15 seconds.

## Loop

1. Work on a branch, never on main:
   `git checkout -b fix/<topic>`
2. Make the smallest fix. Keep the protected features listed in CLAUDE.md.
3. Commit and push:
   `git -c push.negotiate=true push -u origin fix/<topic>`
4. Start the pipeline on that branch (pushes to non-main branches do not trigger it):
   `gh workflow run ci.yml -R FieldPartnerLLC/fieldpartner --ref fix/<topic>`
5. Find the run and poll only the iPhone job (it takes about 4 to 10 minutes):
   ```
   RUN=$(gh run list -R FieldPartnerLLC/fieldpartner --branch fix/<topic> --limit 1 --json databaseId -q '.[0].databaseId')
   gh run view $RUN -R FieldPartnerLLC/fieldpartner --json jobs -q '.jobs[] | select(.name|startswith("iPhone")) | .status+" "+(.conclusion//"")'
   ```
6. On failure, read the full log. The job log only shows filtered lines, so download the artifact:
   ```
   gh run download $RUN -R FieldPartnerLLC/fieldpartner -n ios-simulator -D /tmp/ios-art
   grep -n "error:" /tmp/ios-art/xcodebuild.log | sort -u | head -40
   grep -n "configure failed\|Fatal error" /tmp/ios-art/xcodebuild.log
   ```
   `ios-launch.png` is a screenshot of the app 15 seconds after launch. Look at it.
7. Fix, commit, push, start the pipeline again. Repeat until the job says
   `PASS: built, tested, installed and opened on iPhone ...`.

## Gotchas

- The workflow's concurrency group is `ci-<ref>` with cancel-in-progress. Starting a second run on the same branch cancels the running one. Wait for the result, or push without starting a run.
- Compile errors are reported per file. After one is fixed, new ones in other files may appear.
- `swift test` builds for macOS: async URLSession calls need the `platforms` floor in `ios/Package.swift`.
- LiveKit Swift `from: 2.19.0` resolves to the newest 2.x. APIs can become async: add `await` where the compiler says so.
- In the unsigned simulator build, `Wearables.configure()` throws `WearablesError(rawValue: 0)` (internalError). The app must not touch `Wearables.shared` unless `GlassesManager.sdkReady` is true.
- Lines like "Class ... is implemented in both MWDATMockDevice and MWDATCamera" are noise from Meta's frameworks.
- macOS runner queues can be slow. Do not start more than 2 or 3 iOS runs at once.
