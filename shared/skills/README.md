# Trade skills

One folder per trade, in the Agent Skills format (a `SKILL.md` with a name and description).
Claude reads the skill for the job's trade, plus the company's approved lessons for that trade,
before it answers a technician's hard question and when it writes reports and lessons.

Edit these in plain English. Your best technicians are the right people to write them.
Changes go live on the next deploy (`git push`).

A trade without a folder here still works: the server builds its skill from `shared/trades.json`.
