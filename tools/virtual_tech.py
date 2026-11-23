#!/usr/bin/env python3
"""
Virtual technician: an end-to-end test of the live system with no phone and no glasses.

It does what the phone app does during a visit, against your real backend, LiveKit and
Gemini:
  1. signs in as the demo technician and opens a walk-in visit
  2. records customer consent (video and audio)
  3. joins the visit room as the technician and publishes the demo glasses video
     (a furnace control board blinking 3 flashes) plus a silent microphone
  4. asks the AI partner real questions over the same "fp.cmd" protocol the apps use
  5. checks the answers, tool calls and timings, then ends the visit and waits for the report

Run it on the Mac while ./scripts/start-local.sh is running:
    agent/.venv/bin/python tools/virtual_tech.py

Exit code 0 means every required check passed. A JSON summary is written to logs/.
"""

from __future__ import annotations

import argparse
import asyncio
import fractions
import json
import os
import re
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

import aiohttp
import av
from livekit import rtc

ROOT = Path(__file__).resolve().parent.parent
DEMO_VIDEO = ROOT / "android/app/src/main/assets/demo-glasses.mp4"


def demo_password() -> str | None:
    f = ROOT / "data/demo-accounts.txt"
    if not f.exists():
        return None
    m = re.search(r"Password:\s*(\S+)", f.read_text())
    return m.group(1) if m else None


@dataclass
class Check:
    name: str
    required: bool
    ok: bool = False
    detail: str = ""
    seconds: float | None = None


@dataclass
class Session:
    events: list[dict] = field(default_factory=list)
    ai_text: list[tuple[float, str]] = field(default_factory=list)  # (time, full segment text)
    first_ai_chunk_at: list[float] = field(default_factory=list)
    agent_identity: str | None = None


class Api:
    def __init__(self, base: str) -> None:
        self.base = base.rstrip("/")
        self.token: str | None = None
        self.http = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=30))

    async def call(self, method: str, path: str, body: dict | None = None) -> dict:
        headers = {"Authorization": f"Bearer {self.token}"} if self.token else {}
        async with self.http.request(method, self.base + path, json=body if body is not None else ({} if method == "POST" else None), headers=headers) as r:
            data = await r.json(content_type=None)
            if r.status >= 400:
                raise RuntimeError(f"{method} {path} -> {r.status}: {data}")
            return data

    async def close(self) -> None:
        await self.http.close()


async def publish_video(source: rtc.VideoSource, stop: asyncio.Event, path: Path) -> None:
    """Loops the demo video into the room at its native frame rate, like the glasses do."""
    while not stop.is_set():
        container = av.open(str(path))
        stream = container.streams.video[0]
        fps = float(stream.average_rate or fractions.Fraction(15, 1))
        interval = 1.0 / fps
        next_at = time.monotonic()
        for frame in container.decode(stream):
            if stop.is_set():
                break
            yuv = frame.to_ndarray(format="yuv420p")
            vf = rtc.VideoFrame(frame.width, frame.height, rtc.VideoBufferType.I420, yuv.tobytes())
            source.capture_frame(vf)
            next_at += interval
            await asyncio.sleep(max(0, next_at - time.monotonic()))
        container.close()


async def publish_silence(source: rtc.AudioSource, stop: asyncio.Event) -> None:
    samples = 480  # 10 ms at 48 kHz
    silence = bytes(samples * 2)
    while not stop.is_set():
        await source.capture_frame(rtc.AudioFrame(silence, 48000, 1, samples))


async def wait_for(pred, timeout: float, step: float = 0.2) -> bool:
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if pred():
            return True
        await asyncio.sleep(step)
    return False


async def run(args: argparse.Namespace) -> int:
    checks: list[Check] = []
    api = Api(args.server)
    s = Session()
    room = rtc.Room()
    stop = asyncio.Event()
    tasks: list[asyncio.Task] = []
    visit_id = None
    started = time.monotonic()

    def check(name: str, required: bool, ok: bool, detail: str = "", seconds: float | None = None) -> None:
        c = Check(name, required, ok, detail, round(seconds, 2) if seconds is not None else None)
        checks.append(c)
        mark = "PASS" if ok else ("FAIL" if required else "WARN")
        t = f" ({c.seconds}s)" if c.seconds is not None else ""
        print(f"  [{mark}] {name}{t}{': ' + detail if detail else ''}", flush=True)

    try:
        print("\n1. Backend")
        password = args.password or demo_password()
        if not password:
            print("Could not find the demo password. Pass --password or run ./scripts/start-local.sh once.")
            return 2
        login = await api.call("POST", "/auth/login", {"email": args.email, "password": password})
        api.token = login["token"]
        check("technician signs in", True, True, login["user"]["name"])
        job = (await api.call("POST", "/jobs", {"trade": "hvac", "title": "Virtual tech: no heat", "complaint": "Furnace starts then shuts off, blinking light on the board", "address": "Test bench"}))["job"]
        visit = (await api.call("POST", f"/jobs/{job['id']}/visits"))["visit"]
        visit_id = visit["id"]
        check("visit opened", True, True, visit_id)
        await api.call("POST", f"/visits/{visit_id}/consent", {"kind": "service_and_recording", "choice": "video_audio", "privateAreasOk": False, "signerName": "Virtual Customer", "signerRole": "owner", "language": "en"})
        check("consent recorded", True, True)
        t0 = time.monotonic()
        ticket = await api.call("POST", f"/visits/{visit_id}/live")
        check("live ticket issued (agent dispatched)", True, True, ticket["room"], time.monotonic() - t0)

        print("\n2. Join the room as the technician")

        @room.on("participant_connected")
        def _p(p: rtc.RemoteParticipant) -> None:
            if p.identity.startswith("agent") or p.kind == rtc.ParticipantKind.PARTICIPANT_KIND_AGENT:
                s.agent_identity = p.identity

        def on_event(reader: rtc.TextStreamReader, identity: str) -> None:
            async def go() -> None:
                text = await reader.read_all()
                try:
                    e = json.loads(text)
                except json.JSONDecodeError:
                    return
                e["_t"] = time.monotonic()
                s.events.append(e)
                if args.verbose:
                    print(f"      event: {text}", flush=True)

            asyncio.create_task(go())

        def on_transcription(reader: rtc.TextStreamReader, identity: str) -> None:
            attrs = reader.info.attributes or {}
            mine = {pub.sid for pub in room.local_participant.track_publications.values()}
            from_ai = identity == s.agent_identity or identity.startswith("agent")
            if not from_ai or attrs.get("lk.transcribed_track_id") in mine:
                return

            async def go() -> None:
                buf = ""
                first = True
                async for chunk in reader:
                    if first:
                        s.first_ai_chunk_at.append(time.monotonic())
                        first = False
                    buf += chunk
                if buf.strip():
                    s.ai_text.append((time.monotonic(), buf.strip()))
                    print(f"      AI: {buf.strip()}", flush=True)

            asyncio.create_task(go())

        room.register_text_stream_handler("fp.event", on_event)
        room.register_text_stream_handler("lk.transcription", on_transcription)
        t0 = time.monotonic()
        await room.connect(ticket["url"], ticket["token"])
        check("connected to LiveKit", True, True, ticket["url"], time.monotonic() - t0)

        vsrc = rtc.VideoSource(504, 896)
        vtrack = rtc.LocalVideoTrack.create_video_track("glasses", vsrc)
        await room.local_participant.publish_track(vtrack, rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_CAMERA))
        asrc = rtc.AudioSource(48000, 1)
        atrack = rtc.LocalAudioTrack.create_audio_track("mic", asrc)
        await room.local_participant.publish_track(atrack, rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_MICROPHONE))
        tasks += [asyncio.create_task(publish_video(vsrc, stop, Path(args.video))), asyncio.create_task(publish_silence(asrc, stop))]
        check("publishing glasses video + mic", True, True, Path(args.video).name)

        for p in room.remote_participants.values():
            if p.identity.startswith("agent") or p.kind == rtc.ParticipantKind.PARTICIPANT_KIND_AGENT:
                s.agent_identity = p.identity
        t0 = time.monotonic()
        ok = await wait_for(lambda: s.agent_identity is not None, 30)
        check("AI partner joined the room", True, ok, s.agent_identity or "agent never joined. Is agent/main.py dev running?", time.monotonic() - t0)
        if not ok:
            return 1
        ok = await wait_for(lambda: any(e.get("type") == "assistant_state" and e.get("reason") == "ready" for e in s.events), 30)
        check("AI partner ready", True, ok, "" if ok else "no 'ready' state on fp.event")
        await asyncio.sleep(2)  # let a few frames reach the agent

        async def ask(question: str, expect: str | None, tool: str | None, name: str, required: bool, timeout: float = 45) -> None:
            n_text = len(s.ai_text)
            n_first = len(s.first_ai_chunk_at)
            n_events = len(s.events)
            print(f"\n   Q: {question}", flush=True)
            t0 = time.monotonic()
            await room.local_participant.send_text(json.dumps({"type": "ask", "text": question}), topic="fp.cmd")

            def done() -> bool:
                said = " ".join(t for _, t in s.ai_text[n_text:]).lower()
                tool_ok = tool is None or any(e.get("type") == "tool" and e.get("name") == tool for e in s.events[n_events:])
                text_ok = expect is None or re.search(expect, said) is not None
                return tool_ok and text_ok and (expect is None or len(s.ai_text) > n_text)

            ok = await wait_for(done, timeout)
            said = " ".join(t for _, t in s.ai_text[n_text:])
            first = s.first_ai_chunk_at[n_first] - t0 if len(s.first_ai_chunk_at) > n_first else None
            detail = f"first words after {first:.1f}s" if first is not None else "no spoken answer"
            if not ok:
                detail += f"; expected {expect or tool}; got: {said[:200]!r}"
            check(name, required, ok, detail, time.monotonic() - t0)
            await asyncio.sleep(3)

        print("\n3. Ask the AI partner")
        await ask("What is the status light on the control board doing? Count the flashes and tell me what that code means.", r"pressure", None, "reads the blinking flash code from video", True)
        await ask("Read me the model number and serial number on the rating plate.", r"58\s*sta|2419", None, "reads the equipment rating plate", True)
        await ask("Save a reading: carbon monoxide ambient, 9 ppm.", None, "save_reading", "saves a reading with a tool call", True)
        await ask("What should I check first for that code, one step at a time?", r"hose|tube|vent|inducer|switch|port|drain", None, "gives a first diagnostic step", False)
        await ask(
            "Ask the senior expert: the inducer motor runs but the pressure switch never closes. What should I test next?",
            None,
            "senior_expert",
            "hands a diagnosis question to the senior expert (Claude)",
            False,
            timeout=60,
        )
        await ask("I smell gas near the burners. What do I do?", r"stop|leave|out|shut|evacuat|911|gas company|don.?t", None, "gas smell triggers a stop instruction", True)

        print("\n4. Privacy pause")
        await room.local_participant.send_text(json.dumps({"type": "camera", "allowed": False}), topic="fp.cmd")
        await ask("What do you see in front of me right now?", r"can.?t see|camera|paused|off|not able|unable|don.?t have", None, "AI does not describe video while camera is paused", False)
        await room.local_participant.send_text(json.dumps({"type": "camera", "allowed": True}), topic="fp.cmd")

        print("\n5. Stop command")
        n = len(s.events)
        await room.local_participant.send_text(json.dumps({"type": "stop"}), topic="fp.cmd")
        ok = await wait_for(lambda: any(e.get("type") == "assistant_state" and e.get("reason") == "stopped" for e in s.events[n:]), 10)
        check("'partner, stop' silences the AI", True, ok)

        print("\n6. End the visit and build the report")
        await room.local_participant.send_text(json.dumps({"type": "end"}), topic="fp.cmd")
        stop.set()
        await asyncio.sleep(1)
        await room.disconnect()
        await api.call("POST", f"/visits/{visit_id}/end")
        t0 = time.monotonic()
        report = None
        while time.monotonic() - t0 < 90:
            r = await api.call("GET", f"/visits/{visit_id}/report")
            if r.get("report") or r.get("status") == "failed":
                report = r
                break
            await asyncio.sleep(2)
        rep = (report or {}).get("report") or {}
        ok = bool(rep.get("summary"))
        check("AI drafts the service report", True, ok, (rep.get("summary") or str(report))[:160], time.monotonic() - t0)

        firsts = [s.first_ai_chunk_at[i] for i in range(len(s.first_ai_chunk_at))]
        print(f"\nAI spoke {len(s.ai_text)} times. Events received: {len(s.events)}.")
        return 0 if all(c.ok for c in checks if c.required) else 1
    except Exception as e:  # noqa: BLE001
        check("run completed", True, False, f"{type(e).__name__}: {e}")
        return 1
    finally:
        stop.set()
        for t in tasks:
            t.cancel()
        try:
            await room.disconnect()
        except Exception:  # noqa: BLE001
            pass
        await api.close()
        out = ROOT / "logs" / f"virtual-tech-{int(time.time())}.json"
        out.parent.mkdir(exist_ok=True)
        out.write_text(json.dumps({"visit": visit_id, "seconds": round(time.monotonic() - started, 1), "checks": [c.__dict__ for c in checks], "ai": [t for _, t in s.ai_text]}, indent=2))
        passed = sum(1 for c in checks if c.ok)
        print(f"\nResult: {passed}/{len(checks)} checks passed. Details: {out}")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--server", default=os.environ.get("FP_SERVER", "http://127.0.0.1:8080"))
    p.add_argument("--email", default="tech@fieldpartner.local")
    p.add_argument("--password", default=None)
    p.add_argument("--video", default=str(DEMO_VIDEO), help="glasses video to play (any format PyAV reads)")
    p.add_argument("-v", "--verbose", action="store_true", help="print every agent event")
    args = p.parse_args()
    sys.exit(asyncio.run(run(args)))


if __name__ == "__main__":
    main()
