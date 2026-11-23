---
name: field-test
description: Use when running FieldPartner's real-world field test with real glasses, phones and equipment, or recording pass and fail results from it.
---

# Field test

Source: `docs/FIELD-TEST-CHECKLIST.md`, 110 numbered checks in sections A to M. A fail in A (Safety), D (Consent and privacy) or F (Escalation) blocks field use. Automated tests already cover server and agent logic; this list is what only the real world can test.

## Before the test

1. Live server healthy: `curl -s https://fieldpartner-api.onrender.com/healthz` shows `"ok":true`.
2. Company account and a technician invite exist in the portal (Team).
3. App installed (iphone-install or android-build-install skill), setup finished, glasses registered (glasses-debug skill).
4. Phone and glasses fully charged. Note starting battery for section H.

## Run order for a first session (about 2 hours)

1. A (safety) items 1 to 5, using spoken readings. Fast and blocking.
2. D (consent) 41 to 47 on one walk-in visit.
3. B (wake word) 16 to 23, then 28 in a noisy room.
4. C (seeing) 33 and 37 with a real nameplate and low light.
5. F (escalation) 63 to 66 with a second person in the portal.
6. I (report) 86 to 89 at the end of the visit.
7. H 81 partly: battery of glasses and phone after a 60-minute visit.

## Recording results

Keep a results file next to the checklist, one line per item:
`<number> | Pass or Fail | phone model, app build, glasses model | note`
Example: `28 | Fail | iPhone 15, build 1.0.0, Ray-Ban Meta Gen 2 | caught 14 of 20 with the inducer running`
Then:
- Turn every Fail into a GitHub issue with the item number in the title.
- Update the launch dashboard (progress-dashboard skill): "Meta glasses working on real hardware" and the phone test rows.

## Things to measure, not just pass or fail

- Seconds from wake phrase to first word of the answer (target under 4 on Wi-Fi, under 6 on 2-bar LTE).
- Glasses battery minutes per visit hour while streaming.
- Cellular data per visit hour (phone Settings > Cellular, reset the counter first). Expect about 0.45 GB at 5 fps and 1 Mbps.
- False wakes per hour with a TV on.
