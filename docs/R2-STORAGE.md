# Recordings on Cloudflare R2

Today the live server keeps recordings on its 10 GB Render disk (`/data/media`). One hour of visit
video is about 0.7 GB, so the disk holds about 14 hours of recorded visits. Before real visits,
move recordings to Cloudflare R2 (free up to 10 GB-month, then about $0.015 per GB-month, no
download fees).

Old recordings keep playing during the move: when a file is still on the disk and not yet in the
bucket, the server serves the disk copy. After the copy script runs, everything comes from R2.

## 1. Cloudflare

1. dash.cloudflare.com > R2 Object Storage. The first time, Cloudflare asks for a payment method.
2. Create bucket `fieldpartner-media`, location Automatic. Leave public access off: the server hands
   out short-lived signed links.
3. R2 > Manage API tokens > Create API token: permission **Object Read & Write**, applied to
   `fieldpartner-media` only. Copy the **Access Key ID** and **Secret Access Key** (shown once).
4. Note your **Account ID** (R2 overview page, right side).

## 2. Render (fieldpartner-api > Environment)

| Key | Value |
|---|---|
| `S3_BUCKET` | `fieldpartner-media` |
| `S3_REGION` | `auto` |
| `S3_ENDPOINT` | `https://<account_id>.r2.cloudflarestorage.com` |
| `S3_ACCESS_KEY_ID` | the Access Key ID from step 1.3 |
| `S3_SECRET_ACCESS_KEY` | the Secret Access Key from step 1.3 |
| `CLOUD_RECORDING` | `true` to also save LiveKit's cloud copy of each consented visit to R2, `false` to skip it |

Save. Render redeploys. Open `https://fieldpartner-api.onrender.com/healthz` and check
`"storage":"s3"`. New uploads now go straight from the phone to R2.

## 3. Copy the old recordings

Render > fieldpartner-api > Shell:

```
cd /app
tsx src/scripts/migrate-local-to-s3.ts --dry-run   # lists what it would copy
tsx src/scripts/migrate-local-to-s3.ts             # copies and checks each file's size
```

The last line reads like `scanned 42, copied 42, already in bucket 0, failed 0, 3.10 GB, missing everywhere 0`.

- Safe to run again: files already in the bucket with the same size are skipped.
- `failed` above 0: run it again. It prints each failure.
- `missing everywhere` lists files the database points to that are on neither the disk nor R2.
- It never deletes anything from the disk. Once a run shows `failed 0`, you can delete
  `/data/media` by hand, or keep it until you drop the disk.

Locally (any machine with the same env vars set): `cd backend && npm run media:to-s3 -- --dry-run`.

## Notes

- Each file is one upload, so the limit is 5 GB per file. Visit recordings are saved in segments
  well under that.
- Going back to the disk: remove `S3_BUCKET`. Files uploaded to R2 meanwhile stay in R2 and won't
  play from the disk.
