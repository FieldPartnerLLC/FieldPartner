---
name: release-and-deploy
description: Use when merging FieldPartner changes to main, deploying the live server on Render, or publishing the latest Android APK.
---

# Release and deploy

## The rule

Merge to main only after every CI job is green on the branch. A push to main runs the full pipeline, and only a fully green main deploys.

## Steps

1. Run the pipeline on the branch and check all jobs:
   ```
   gh workflow run ci.yml -R FieldPartnerLLC/fieldpartner --ref <branch>
   RUN=$(gh run list -R FieldPartnerLLC/fieldpartner --branch <branch> --limit 1 --json databaseId -q '.[0].databaseId')
   gh run view $RUN -R FieldPartnerLLC/fieldpartner --json jobs -q '.jobs[] | .name+": "+(.conclusion//.status)'
   ```
   Expect success for: Backend tests, AI agent tests, Portal build, Android build and tests, Android emulator smoke test, iPhone build and tests. "Publish the latest APK" is skipped on branches.
2. Ask the user before merging to main: it restarts the live server.
3. Merge (fast-forward when possible) and push main:
   ```
   git checkout main && git pull --ff-only && git merge --ff-only <branch>
   git -c push.negotiate=true push origin main
   ```
4. What happens on main:
   - The CI pipeline runs again.
   - The release job replaces the GitHub release `latest` with `FieldPartner-android.apk`.
   - Render deploys the API, agent and portal only after the checks pass (`autoDeployTrigger: checksPass` in `render.yaml`). The API has a disk, so expect a short restart.
5. Check the live server:
   `curl -s https://fieldpartner-api.onrender.com/healthz`
   Expect `"ok":true` with database, livekit, gemini, claude and email true. `twilio` is false until SMS paging is set up.

## Never

- Push to main with a red job, or to skip the pipeline.
- Change Render environment values from code. Secrets are set in the Render dashboard (`sync: false` in `render.yaml`).
- Commit `config.env`, `backend/.env` or `ios/Config/Local.xcconfig`.

## Rollback

Revert the commit on main and push; the pipeline and Render redeploy the previous code. Render's dashboard also has "Rollback" per service.
