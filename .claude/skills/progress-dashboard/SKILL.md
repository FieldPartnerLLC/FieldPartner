---
name: progress-dashboard
description: Use when updating the "FieldPartner Launch Progress" Claude dashboard after work is finished, a status changes, or the user asks what is built and what is left.
---

# Update the launch progress dashboard

Dashboard: "FieldPartner Launch Progress", artifact slug `f39306d3-5afe-4890-bcff-2220f9092952`, https://claude.ai/artifact/X5W2scft2M2CyBMMgrkvHP. It is a Data-type artifact: its numbers come from one JSON file, the `progress` dataset. The page code (`files/index.html` in its store) computes the tiles and needs no change for status updates.

## Row format (progress.json is an array of these)

```
{"area": "iPhone app", "item": "Installed on your iPhone", "status": "not started",
 "pct": 0, "weight": 2, "pilot": "no", "owner": "You", "note": "Plain sentence"}
```
- `status`: exactly one of `done`, `in progress`, `waiting on you`, `not started`. Other values break the filters and counts.
- `pct`: 0 to 100. `weight`: relative size of the work. Tiles are weight-averaged percents.
- `pilot`: `yes` counts toward "Ready to install and use on Android".
- `waiting on you` rows appear in "What only you can do next". Use it only for items the user can do right now.
- Areas show in first-seen order, so keep rows grouped by area.

## Steps

1. Read the current file: `read_asset` with the artifact slug and the id from the dataset's `source.url` (`db_get datasets/progress`).
2. Edit rows locally, write `progress.json`, and check the numbers:
   `python3 -c "import json;r=json.load(open('progress.json'));w=lambda x:round(sum(i['weight']*i['pct'] for i in x)/sum(i['weight'] for i in x));print(w([i for i in r if i['pilot']=='yes']),w(r))"`
3. `upload_asset` with the slug and the file. Note the returned `/_blob/<id>` url.
4. `db_write` one `update` op on `datasets/progress` with `ifVersion` from step 1:
   `{"source": {"kind": "file", "format": "json", "name": "progress.json", "url": "/_blob/<id>"}, "updated": {"at": "<ISO time>", "by": "Claude"}, "description": "<what changed, as of date>"}`
5. To change a label on the page, read `files/index.html` again first (the user may have edited it), then `db_str_replace` on its `text` field.

## Honesty rules

- Mark `done` only for what a build, test run or live check proved.
- Put the evidence in `note` (for example "19 unit tests pass on the iPhone 17 Pro simulator").
- When a decision removes an item (like not publishing to the stores), remove the row and say so in the description.
