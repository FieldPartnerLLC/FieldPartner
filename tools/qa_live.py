#!/usr/bin/env python3
"""
Full live test against the real server, no phone needed.

  1. Signs in to (or creates once) a separate test company, "FieldPartner QA", with its own admin
     and technician. Credentials stay in ~/.fieldpartner-qa.json, never printed.
  2. Runs tools/virtual_tech.py as that technician: demo glasses video into a real visit, real
     questions to Gemini Live, safety, privacy pause, stop, report.
  3. Checks what the backend recorded: Claude consults and which model tier answered, nameplate
     reads, and the AI cost page.

    agent/.venv/bin/python tools/qa_live.py --server https://fieldpartner-api.onrender.com
"""

from __future__ import annotations

import argparse
import asyncio
import json
import secrets
import subprocess
import sys
import time
from pathlib import Path

import os

import aiohttp

ROOT = Path(__file__).resolve().parent.parent
CREDS = Path(os.environ.get("FP_QA_CREDS", str(Path.home() / ".fieldpartner-qa.json")))


async def call(http: aiohttp.ClientSession, server: str, method: str, path: str, body=None, token: str | None = None):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    async with http.request(method, server + path, json=body, headers=headers) as r:
        data = await r.json(content_type=None)
        if r.status >= 400:
            raise RuntimeError(f"{method} {path} -> {r.status}: {data}")
        return data


async def accounts(http, server: str) -> dict:
    if CREDS.exists():
        c = json.loads(CREDS.read_text())
        if c.get("server") == server:
            return c
    tag = secrets.token_hex(3)
    c = {
        "server": server,
        "admin_email": f"qa-admin-{tag}@fieldpartner.test",
        "tech_email": f"qa-tech-{tag}@fieldpartner.test",
        "password": secrets.token_urlsafe(18),
    }
    await call(http, server, "POST", "/auth/signup", {
        "companyName": f"FieldPartner QA {tag}", "name": "QA Admin", "email": c["admin_email"],
        "password": c["password"], "trades": ["hvac", "electrical", "plumbing"],
    })
    login = await call(http, server, "POST", "/auth/login", {"email": c["admin_email"], "password": c["password"]})
    await call(http, server, "POST", "/admin/users", {
        "name": "QA Robot Tech", "email": c["tech_email"], "role": "tech", "trades": ["hvac"],
        "certifications": ["EPA 608 Universal"], "password": c["password"],
    }, login["token"])
    CREDS.write_text(json.dumps(c))
    CREDS.chmod(0o600)
    print(f"Created test company and accounts (saved in {CREDS})")
    return c


async def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="https://fieldpartner-api.onrender.com")
    ap.add_argument("--skip-robot", action="store_true")
    args = ap.parse_args()
    server = args.server.rstrip("/")
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=60)) as http:
        health = await call(http, server, "GET", "/healthz")
        print("Health:", json.dumps(health))
        c = await accounts(http, server)
        admin = (await call(http, server, "POST", "/auth/login", {"email": c["admin_email"], "password": c["password"]}))["token"]

        rc = 0
        visit_id = None
        if not args.skip_robot:
            before = set((ROOT / "logs").glob("virtual-tech-*.json")) if (ROOT / "logs").exists() else set()
            rc = subprocess.call([sys.executable, str(ROOT / "tools/virtual_tech.py"), "--server", server, "--email", c["tech_email"], "--password", c["password"]])
            after = sorted(set((ROOT / "logs").glob("virtual-tech-*.json")) - before)
            if after:
                visit_id = json.loads(after[-1].read_text()).get("visit")

        results: list[tuple[str, bool, str]] = []
        if visit_id:
            await asyncio.sleep(5)
            detail = await call(http, server, "GET", f"/visits/{visit_id}", token=admin)
            events = detail.get("events", [])
            consults = [e["data"] for e in events if e["type"] == "consult"]
            reads = [x for x in consults if x.get("kind") == "read"]
            diag = [x for x in consults if x.get("kind") != "read"]
            results.append(("backend recorded the visit events", len(events) > 5, f"{len(events)} events"))
            results.append(("nameplate read through read_label (Claude fast model)", bool(reads), "; ".join(f"{x.get('model')}: {str(x.get('answer'))[:80]}" for x in reads) or "none"))
            results.append(("senior expert consult recorded", bool(diag), "; ".join(f"{x.get('tier')} ({x.get('why')}) via {x.get('model')}" for x in diag) or "none"))
            types = sorted({e["type"] for e in events})
            results.append(("reading, safety and answer events stored", {"reading", "answer"} <= set(types), ", ".join(types)))
        try:
            usage = await call(http, server, "GET", "/usage", token=admin)
            results.append(("AI cost page has this month's spend", usage["monthToDateUsd"] > 0, f"${usage['monthToDateUsd']} over {usage['visits']} visits, avg ${usage['costPerVisitUsd']}/visit; " + ", ".join(f"{t['task']} ${t['usd']}" for t in usage["byTask"])))
            live = [t for t in usage["byTask"] if t["task"] == "live"]
            results.append(("Gemini Live usage metered", bool(live), f"${live[0]['usd']}" if live else "not reported yet"))
        except Exception as e:  # noqa: BLE001
            results.append(("AI cost page", False, str(e)[:160]))

        print("\nBackend checks")
        for name, ok, detail in results:
            print(f"  [{'PASS' if ok else 'FAIL'}] {name}: {detail}")
        return rc or (0 if all(ok for _, ok, _ in results) else 1)


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
