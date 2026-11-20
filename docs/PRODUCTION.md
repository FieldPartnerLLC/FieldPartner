# Going to production

Tonight's setup runs everything on one Mac. For real technicians you need the pieces below.

## 1. Hosting

| Piece | Recommended | Notes |
|---|---|---|
| Backend | Any container host (AWS App Runner or ECS, Fly.io, Render). `backend/Dockerfile`. | Set `DATABASE_URL` to Postgres. Run one instance until escalation timers move to a queue (they already survive restarts through the 30 s sweep). |
| Database | Postgres 15+ (AWS RDS, Neon, Supabase) | Migrations run on start. |
| File storage | S3 or Cloudflare R2 | Set `S3_*`. Turn on `CLOUD_RECORDING=true` to also record visits through LiveKit Egress. Set a lifecycle rule to match each company's retention. |
| Live video | LiveKit Cloud | Free tier for testing; paid for production. Add a webhook to `https://<api>/livekit/webhook`. |
| AI agent | `agent/Dockerfile` on any container host, or LiveKit Cloud Agents | `python main.py start`. Scale by running more copies. |
| Portal | Static hosting (S3 + CloudFront, Vercel, Netlify) | `cd portal && VITE_API_URL=https://<api> npm run build`, upload `dist/`. |
| HTTPS | Required for production | The iOS app allows plain http only for local testing. |

## 2. Accounts and keys

- Gemini: paid tier only. Free tier terms allow Google to use the data.
- Twilio: buy a number; apply for a WhatsApp sender (approval takes days). Until approved, paging falls back to SMS.
- SMTP for report emails (Amazon SES, Postmark, SendGrid).
- Apple Developer Program ($99/year) for TestFlight.
- Meta Wearables Developer Center: register the app, get the Meta App ID and Client Token, put them in `ios/Config/Local.xcconfig` and `android/local.properties`. Needed when techs' Meta AI apps are not in Developer Mode. Create a release channel to share with your techs.

## 3. Distribution to technicians

1. Xcode: Product > Archive > Distribute > TestFlight. Android: `./scripts/build-android.sh release`, upload the .aab to Play Console > Internal testing.
2. Add techs as internal testers (up to 100) in App Store Connect.
3. Each tech installs TestFlight, then FieldPartner, then connects their glasses.
4. Meta release channel: add each tech so their glasses accept the app.

## 4. Before real customers

- Attorney review of the consent text in `ios/FieldPartner/Visit/ConsentText.swift` and `android/.../core/ConsentText.kt` (keep them identical) for every state you operate in. Questions to ask are in the spec doc.
- Retention period per company (`retention_days`) and a deletion job.
- Each company: wake phrase, CO stop level, supervisor name (`settings.supervisorName`), expert phone and WhatsApp numbers, escalation order.
- Trade profiles reviewed by a licensed lead in each trade (`shared/trades.json`, or `PUT /admin/trades/:id`).
- Run `docs/FIELD-TEST-CHECKLIST.md`.

## 5. Store submission

Step by step for Google Play and the App Store, including signing keys, the foreground service declaration and reviewer notes: `docs/EMULATOR-AND-STORES.md` sections 6 to 8.
