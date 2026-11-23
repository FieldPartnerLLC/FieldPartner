---
name: glasses-debug
description: Use when the Meta glasses will not register, connect or stream video in FieldPartner, or when changing glasses code on iOS or Android (Meta Wearables Device Access Toolkit 1.1).
---

# Meta glasses: setup and debugging

SDK: Meta Wearables Device Access Toolkit (DAT) 1.1. iOS package `https://github.com/meta/meta-wearables-dat-ios` (exactVersion 1.1.0 in `ios/project.yml`). Android from Maven Central. Code: `ios/FieldPartner/Glasses/GlassesManager.swift`, `android/.../glasses/GlassesManager.kt`.

## Before testing on real glasses

1. Glasses paired with the Meta AI app on the same phone, firmware up to date, battery above 10% (the DAT glasses app install fails below 10%).
2. Developer Mode in the Meta AI app: Settings > App Info > tap the app version 5 times > toggle Developer Mode.
3. App config uses Developer Mode values: `MetaAppID` 0 and `ClientToken` 0 (from `META_APP_ID` and `CLIENT_TOKEN` in `Local.xcconfig` on iOS).
4. Only one third-party app can stay registered in Developer Mode. Registering FieldPartner unregisters any other.

## How the pieces connect

- Registration: the app calls `startRegistration()`, which opens Meta AI (needs `fb-viewapp` in `LSApplicationQueriesSchemes` on iOS). Meta AI calls back to `fieldpartner://`; the app passes the URL to `Wearables.shared.handleUrl(url)`.
- Transport: Bluetooth LE for discovery and control. Camera video needs Wi-Fi: the phone joins a network the glasses host. This is a direct link, not the customer's Wi-Fi; the phone still uses cellular data for the internet.
- iOS Wi-Fi video needs `NSLocalNetworkUsageDescription`, `NSBonjourServices` with `_bonjour._tcp`, and the `com.apple.developer.networking.HotspotConfiguration` entitlement. That entitlement needs a paid Apple team. Without it the integration continues over BLE with no streaming.
- Audio: the glasses act as a Bluetooth headset (HFP). FieldPartner listens for its own wake phrase on the phone. "Hey Meta" goes to Meta AI and is unrelated.
- `createSession` throws `DeviceSessionError.notRegistered` until `registrationState` is `.registered`.
- Stream errors to handle: `thermalHot`, `batteryLow`, `peakPowerLimit`.

## iOS specifics

- Call `Wearables.configure()` once at launch. In the unsigned simulator build it throws `WearablesError(rawValue: 0)`. `GlassesManager.sdkReady` guards every use of `Wearables.shared`; never access it before configure succeeds, or the app hits a fatalError.
- Simulator: debug builds pair MockDeviceKit glasses (`enableSimulatedGlasses`) and play `demo-glasses.mp4` as the camera feed.

## Symptoms

| Symptom | Check |
|---|---|
| "Glasses are unavailable on this phone" | configure failed: read the `[FieldPartner] Wearables SDK configure failed` log line |
| Registration does nothing | Developer Mode on? `fb-viewapp` query scheme? URL scheme `fieldpartner` registered? |
| Connected but no video (iOS) | Hotspot entitlement missing (free team) or Local Network permission denied |
| Video stops after a while | heat or battery: stream error, glasses off the face, Bluetooth range |
| Video works, no AI answer | wake phrase not heard over the Bluetooth mic, or the live call is not connected |

Docs: wearables.developer.meta.com (build-integration-ios, permissions-requests, testing-mdk-ios).
