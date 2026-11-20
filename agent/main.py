"""FieldPartner AI agent worker.

One worker process serves many visits. The backend dispatches it into each visit's
LiveKit room. In the room it:
  - stays silent and does not listen until the technician calls it (wake phrase or button),
  - answers with voice through the glasses, seeing the glasses video while active,
  - runs a background hazard check on one frame every few seconds,
  - mutes itself while a human expert is live,
  - writes every reading, note, hazard and answer to the backend for the report.

Env: LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET, GOOGLE_API_KEY,
     BACKEND_URL, AGENT_SERVICE_TOKEN,
     optional GEMINI_LIVE_MODEL, GEMINI_VOICE, SAFETY_MODEL, SAFETY_INTERVAL_SECONDS,
     FOLLOW_UP_SECONDS, AGENT_NAME.
"""

from __future__ import annotations

import asyncio
import io
import json
import logging
import os
import time
from dataclasses import dataclass, field
from typing import Any

from dotenv import load_dotenv
from google import genai
from google.genai import types as genai_types
from livekit import rtc
from livekit.agents import Agent, AgentSession, JobContext, RunContext, WorkerOptions, cli, function_tool
from livekit.agents.voice.room_io import RoomOptions
from livekit.plugins import google
from PIL import Image

from backend import BackendClient
from policy import AssistantGate, GatedSampler, SafetyDeduper, WatcherBudget
from prompts import build_instructions, build_safety_prompt, clean_transcript, parse_safety

load_dotenv()
logger = logging.getLogger("fieldpartner.agent")

EVENT_TOPIC = "fp.event"  # agent -> phone
COMMAND_TOPIC = "fp.cmd"  # phone -> agent


@dataclass
class FrameHolder:
    frame: rtc.VideoFrame | None = None
    arrived_at: float = 0.0  # time.monotonic()


@dataclass
class VisitState:
    visit_id: str
    tech_identity: str
    backend: BackendClient
    room: rtc.Room
    gate: AssistantGate
    frames: FrameHolder = field(default_factory=FrameHolder)
    context: dict[str, Any] = field(default_factory=dict)
    session: AgentSession | None = None
    pending_hints: list[dict[str, Any]] = field(default_factory=list)

    async def to_phone(self, payload: dict[str, Any]) -> None:
        try:
            await self.room.local_participant.send_text(
                json.dumps(payload), topic=EVENT_TOPIC, destination_identities=[self.tech_identity]
            )
        except Exception:
            logger.exception("send to phone failed: %s", payload.get("type"))


def jpeg_from_frame(frame: rtc.VideoFrame, max_side: int = 1024, quality: int = 75) -> bytes:
    rgba = frame.convert(rtc.VideoBufferType.RGBA)
    img = Image.frombuffer("RGBA", (rgba.width, rgba.height), bytes(rgba.data)).convert("RGB")
    img.thumbnail((max_side, max_side))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=quality)
    return buf.getvalue()


# ---------------------------------------------------------------- tools

@function_tool
async def save_reading(ctx: RunContext[VisitState], name: str, value: str, unit: str = "") -> str:
    """Save a measurement the technician reported, e.g. name="Ambient CO", value="0", unit="ppm"."""
    st = ctx.userdata
    data = {"name": name, "value": value, "unit": unit}
    st.backend.event("reading", data)
    st.context.setdefault("readings", []).append(data)
    await st.to_phone({"type": "tool", "name": "save_reading", "data": data})
    return "Logged."


@function_tool
async def save_equipment(
    ctx: RunContext[VisitState],
    equipment_type: str,
    brand: str = "",
    model: str = "",
    serial: str = "",
    year: str = "",
) -> str:
    """Save equipment details read from a nameplate or label. Leave a field empty if it was not readable."""
    st = ctx.userdata
    data = {k: v for k, v in {"type": equipment_type, "brand": brand, "model": model, "serial": serial, "year": year}.items() if v}
    st.backend.event("equipment", data)
    await st.to_phone({"type": "tool", "name": "save_equipment", "data": data})
    return "Saved to the job."


@function_tool
async def add_note(ctx: RunContext[VisitState], text: str) -> str:
    """Save a finding or note for the service report."""
    st = ctx.userdata
    st.backend.event("note", {"text": text})
    await st.to_phone({"type": "tool", "name": "add_note", "data": {"text": text}})
    return "Noted."


@function_tool
async def flag_safety_stop(ctx: RunContext[VisitState], reason: str) -> str:
    """Record a safety stop. Use whenever a stop rule is met."""
    st = ctx.userdata
    st.backend.event("safety", {"level": "critical", "reason": reason, "source": "assistant"})
    await st.to_phone({"type": "safety", "level": "critical", "reason": reason})
    return "Safety stop recorded."


@function_tool
async def call_supervisor(ctx: RunContext[VisitState], reason: str) -> str:
    """Page the on-call supervisor to join the technician live."""
    st = ctx.userdata
    try:
        res = await st.backend.escalate(reason)
    except Exception as e:
        logger.warning("escalate failed: %s", e)
        await st.to_phone({"type": "escalation", "status": "failed", "reason": reason})
        return "I couldn't reach the server to page the supervisor. Tell the technician to call them directly."
    await st.to_phone({"type": "escalation", "status": res.get("status"), "paged": res.get("paged"), "reason": reason})
    who = res.get("paged") or "the supervisor"
    return f"Paged {who}."


@function_tool
async def draft_estimate(
    ctx: RunContext[VisitState], problem: str, cause: str, recommended_repair: str, parts: str = ""
) -> str:
    """Put an estimate draft on the technician's phone screen. Never speak prices."""
    st = ctx.userdata
    data = {"problem": problem, "cause": cause, "repair": recommended_repair, "parts": parts}
    st.backend.event("estimate", data)
    await st.to_phone({"type": "tool", "name": "draft_estimate", "data": data})
    return "The draft is on the technician's screen. Say only that it's on the screen."


TOOLS = [save_reading, save_equipment, add_note, flag_safety_stop, call_supervisor, draft_estimate]


# ---------------------------------------------------------------- safety watcher

async def safety_watcher(st: VisitState, stop: asyncio.Event) -> None:
    if not os.environ.get("GOOGLE_API_KEY"):
        logger.warning("GOOGLE_API_KEY missing: safety watcher off")
        return
    client = genai.Client()
    model = os.environ.get("SAFETY_MODEL", "gemini-3.5-flash-lite")
    budget = WatcherBudget(interval_seconds=float(os.environ.get("SAFETY_INTERVAL_SECONDS", "5")))
    dedupe = SafetyDeduper()
    prompt = build_safety_prompt(st.context.get("trade"))
    consecutive_errors = 0
    while not stop.is_set():
        await asyncio.sleep(0.5)
        frame = st.frames.frame
        if frame is None or st.gate.expert_live:
            continue
        if not budget.due(st.frames.arrived_at, st.gate.camera_allowed):
            continue
        try:
            jpeg = await asyncio.to_thread(jpeg_from_frame, frame, 768, 70)
            resp = await asyncio.wait_for(
                client.aio.models.generate_content(
                    model=model,
                    contents=[genai_types.Part.from_bytes(data=jpeg, mime_type="image/jpeg"), prompt],
                    config=genai_types.GenerateContentConfig(temperature=0.0, max_output_tokens=40),
                ),
                timeout=8,
            )
            consecutive_errors = 0
        except Exception as e:
            consecutive_errors += 1
            if consecutive_errors in (1, 10):
                logger.warning("safety check failed (%d): %s", consecutive_errors, e)
            if consecutive_errors == 3:
                await st.to_phone({"type": "watcher", "status": "offline"})
            continue
        level, reason = parse_safety(resp.text)
        if not dedupe.should_report(level, reason):
            continue
        st.backend.event("safety" if level == "critical" else "hint", {"level": level, "reason": reason, "source": "watcher"})
        if level == "critical":
            await st.to_phone({"type": "safety", "level": "critical", "reason": reason, "source": "watcher"})
            if st.session and not st.gate.expert_live:
                try:
                    st.session.interrupt()
                    st.session.generate_reply(
                        instructions=f'Say exactly this and nothing else: "Stop. {reason}." Then wait.',
                        allow_interruptions=True,
                    )
                except Exception:
                    logger.exception("could not speak safety alert")
        else:
            hint = {"id": f"h{int(time.time() * 1000)}", "reason": reason}
            st.pending_hints.append(hint)
            await st.to_phone({"type": "hint", **hint})


# ---------------------------------------------------------------- entrypoint

async def entrypoint(ctx: JobContext) -> None:
    meta = json.loads(ctx.job.metadata or "{}")
    visit_id = meta.get("visitId") or ctx.room.name.removeprefix("visit-")
    backend = BackendClient(os.environ["BACKEND_URL"], os.environ["AGENT_SERVICE_TOKEN"], visit_id)
    await ctx.connect()

    tech = await ctx.wait_for_participant()
    if not tech.identity.startswith("tech-"):
        # Someone other than the technician arrived first; wait for the tech.
        while True:
            p = await ctx.wait_for_participant()
            if p.identity.startswith("tech-"):
                tech = p
                break
    logger.info("visit %s: technician %s connected", visit_id, tech.identity)

    gate = AssistantGate(follow_up_seconds=float(os.environ.get("FOLLOW_UP_SECONDS", "20")))
    st = VisitState(visit_id=visit_id, tech_identity=tech.identity, backend=backend, room=ctx.room, gate=gate)
    try:
        st.context = await backend.context()
    except Exception as e:
        logger.error("could not load visit context, using defaults: %s", e)
        st.context = {"trade": None, "job": {}, "company": {}, "tech": {}, "visit": {}}
    gate.camera_allowed = (st.context.get("visit") or {}).get("consentChoice") in (None, "video_audio")
    backend.start_pump()

    # ---- frames: keep the latest glasses frame for the hazard watcher
    def start_video_reader(track: rtc.Track) -> None:
        async def read() -> None:
            stream = rtc.VideoStream(track)
            async for ev in stream:
                st.frames.frame = ev.frame
                st.frames.arrived_at = time.monotonic()

        asyncio.create_task(read())

    @ctx.room.on("track_subscribed")
    def _on_track(track: rtc.Track, _pub: rtc.RemoteTrackPublication, participant: rtc.RemoteParticipant) -> None:
        if participant.identity == st.tech_identity and track.kind == rtc.TrackKind.KIND_VIDEO:
            start_video_reader(track)

    for pub in tech.track_publications.values():
        if pub.track and pub.kind == rtc.TrackKind.KIND_VIDEO:
            start_video_reader(pub.track)

    # ---- the realtime model
    model = google.realtime.RealtimeModel(
        model=os.environ.get("GEMINI_LIVE_MODEL", "gemini-3.1-flash-live-preview"),
        voice=os.environ.get("GEMINI_VOICE", "Puck"),
        temperature=0.4,
        input_audio_transcription=genai_types.AudioTranscriptionConfig(),
        output_audio_transcription=genai_types.AudioTranscriptionConfig(),
        context_window_compression=genai_types.ContextWindowCompressionConfig(sliding_window=genai_types.SlidingWindow()),
        session_resumption=genai_types.SessionResumptionConfig(),
    )
    sampler = GatedSampler(gate)
    session: AgentSession = AgentSession(llm=model, userdata=st, video_sampler=sampler)
    st.session = session

    async def set_active(on: bool, reason: str) -> None:
        if on:
            gate.activate()
            sampler.force_next = True
            session.input.set_audio_enabled(True)
        else:
            gate.deactivate()
            session.input.set_audio_enabled(False)
        await st.to_phone({"type": "assistant_state", "state": "listening" if on else "idle", "reason": reason})

    @session.on("agent_state_changed")
    def _agent_state(ev) -> None:
        gate.on_agent_speaking(ev.new_state == "speaking")
        asyncio.create_task(st.to_phone({"type": "assistant_state", "state": ev.new_state}))

    @session.on("user_state_changed")
    def _user_state(ev) -> None:
        gate.on_user_speaking(ev.new_state == "speaking")

    @session.on("conversation_item_added")
    def _item(ev) -> None:
        item = ev.item
        role = getattr(item, "role", None)
        text = clean_transcript(getattr(item, "text_content", None))
        if text and role in ("user", "assistant"):
            backend.event("question" if role == "user" else "answer", {"text": text}, actor="tech" if role == "user" else "ai")

    await session.start(
        agent=Agent(instructions=build_instructions(st.context), tools=TOOLS),
        room=ctx.room,
        room_options=RoomOptions(
            video_input=True,
            audio_input=True,
            participant_identity=st.tech_identity,
            # The phone drops out in basements and elevators. Keep the session for its return.
            close_on_disconnect=False,
        ),
    )
    session.input.set_audio_enabled(False)
    await st.to_phone({"type": "assistant_state", "state": "idle", "reason": "ready"})

    # ---- commands from the phone
    async def handle_command(raw: str) -> None:
        try:
            cmd = json.loads(raw)
        except json.JSONDecodeError:
            return
        kind = cmd.get("type")
        if kind == "ask":
            question = (cmd.get("text") or "").strip()
            if gate.expert_live:
                await st.to_phone({"type": "assistant_state", "state": "muted", "reason": "expert_live"})
                return
            session.interrupt()
            await set_active(True, "ask")
            if question:
                # Give the sampler a beat to send a fresh frame with the question.
                await asyncio.sleep(0.6 if gate.camera_allowed else 0)
                session.generate_reply(user_input=question)
        elif kind == "listen":  # push-to-talk: open the mic and let the model hear the technician directly
            if not gate.expert_live:
                session.interrupt()
                await set_active(True, "button")
        elif kind == "stop":
            session.interrupt()
            await set_active(False, "stopped")
        elif kind == "continue":
            await set_active(True, "continue")
            session.generate_reply(instructions="Repeat the last step you gave, in full, then wait.")
        elif kind == "hint_play":
            if st.pending_hints:
                hint = st.pending_hints.pop(0)
                await set_active(True, "hint")
                session.generate_reply(instructions=f'Say in one short sentence: "{hint["reason"]}." Then wait.')
        elif kind == "camera":
            gate.camera_allowed = bool(cmd.get("allowed"))
        elif kind == "disputed":
            backend.event("disputed", {"text": cmd.get("text", "")}, actor="tech")
        elif kind == "context_refresh":
            try:
                st.context = await backend.context()
                await session.current_agent.update_instructions(build_instructions(st.context))
            except Exception as e:
                logger.warning("context refresh failed: %s", e)
        elif kind == "end":
            await set_active(False, "visit_ended")

    def on_text(reader, participant_identity: str) -> None:  # noqa: ANN001
        if participant_identity != st.tech_identity:
            return

        async def run() -> None:
            text = await reader.read_all()
            await handle_command(text)

        asyncio.create_task(run())

    ctx.room.register_text_stream_handler(COMMAND_TOPIC, on_text)

    # ---- expert presence: the AI goes quiet while a human expert is live
    def update_expert(present: bool) -> None:
        gate.expert_live = present
        session.output.set_audio_enabled(not present)
        if present:
            session.interrupt()
            session.input.set_audio_enabled(False)
            gate.deactivate()
        asyncio.create_task(st.to_phone({"type": "expert", "present": present}))

    @ctx.room.on("participant_connected")
    def _joined(p: rtc.RemoteParticipant) -> None:
        if p.identity.startswith("expert-"):
            update_expert(True)

    @ctx.room.on("participant_disconnected")
    def _left(p: rtc.RemoteParticipant) -> None:
        if p.identity.startswith("expert-"):
            still = any(x.identity.startswith("expert-") for x in ctx.room.remote_participants.values() if x.identity != p.identity)
            update_expert(still)

    if any(p.identity.startswith("expert-") for p in ctx.room.remote_participants.values()):
        update_expert(True)

    # ---- background loops
    stop = asyncio.Event()

    async def follow_up_closer() -> None:
        while not stop.is_set():
            await asyncio.sleep(1)
            if gate.should_close():
                await set_active(False, "follow_up_window_closed")

    tasks = [asyncio.create_task(follow_up_closer()), asyncio.create_task(safety_watcher(st, stop))]

    async def shutdown() -> None:
        stop.set()
        for t in tasks:
            t.cancel()
        await backend.close()

    ctx.add_shutdown_callback(shutdown)


if __name__ == "__main__":
    from livekit.agents import JobExecutorType

    # Small hosts (Render Starter = 512 MB): run visits as threads in one process and keep
    # one warm slot. LiveKit's default starts up to 4 idle processes, which needs about 1 GB.
    opts = {}
    if os.environ.get("AGENT_EXECUTOR", "").lower() == "thread":
        opts["job_executor_type"] = JobExecutorType.THREAD
    if os.environ.get("AGENT_IDLE_PROCESSES"):
        opts["num_idle_processes"] = int(os.environ["AGENT_IDLE_PROCESSES"])
    cli.run_app(
        WorkerOptions(
            entrypoint_fnc=entrypoint,
            agent_name=os.environ.get("AGENT_NAME", "fieldpartner-agent"),
            **opts,
        )
    )
