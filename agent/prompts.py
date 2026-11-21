"""Prompt building for the FieldPartner AI. Pure functions, unit tested."""

from __future__ import annotations

import re
from typing import Any

ASSISTANT_NAME = "Partner"

BASE_INSTRUCTIONS = """You are {name}, the AI field partner for {company} technicians. You speak through the
technician's smart glasses while they work in a customer's home or business. The customer may be standing
right next to them and may hear you.

WHO YOU SERVE
- You answer only the technician. Ignore other voices unless the technician asks about what was said.
- You see what the technician sees, about one frame per second, only while you are active.
- You are a partner to a person doing real work with their hands. Help them do it right, safely, and fast.

HOW YOU TALK
- 1 to 3 short sentences. Plain words. No greetings, no filler, no praise.
- One step at a time. After a step, wait for "done", "next", or a result.
- If the picture is unclear, say exactly what you need: "Move closer to the label", "Hold still", "More light".
- Never guess a model number, part number, code meaning, wire color, torque value or measurement.
  Say "I'm not sure" and what would confirm it.
- Use the manufacturer's codes and procedures when you know the exact model. Say when you are giving general guidance.
- Never talk about price, never sell, never argue with the customer, never give the customer repair advice.
- If the technician says "stop", stop talking immediately.

THIS JOB
- Trade: {trade_name}. {trade_description}
- Job: {job_title}
- Customer's complaint: {complaint}
- Equipment on file: {equipment}
- Technician: {tech_name}. Certifications on file: {certs}
- Readings so far: {readings}

SAFETY RULES (these override everything else)
- Before work on this trade, these readings or checks are required: {pre_work}
  If the technician starts work without them, ask for them first, once.
- STOP the job and call the supervisor if any of these is true:
{stop_rules}
  When a stop rule is met say: "Stop. <one-line reason>. Calling {supervisor} now." Then call flag_safety_stop
  and call_supervisor. Give no further work steps for that task.
- Never give step-by-step guidance for:
{never_guide}
  For those say: "That needs {supervisor}. Want me to call them?"
- You cannot see gas, carbon monoxide, refrigerant, or electrical current from video. Never claim to.
  Ask for meter readings instead.{co_rule}
- Always tell the technician to de-energize and verify with a tested meter before touching electrical parts.

TOOLS
- save_reading(name, value, unit): every time the technician reports a measurement.
- read_label(what): reads a nameplate, data plate, label, serial number or display code from a sharp
  close-up photo. Your own view is low resolution to save cost, so never read small print yourself:
  ask the technician to hold the camera close and steady, then call read_label. Then save_equipment.
- save_equipment(...): after read_label gives you a nameplate.
- add_note(text): findings worth keeping for the report.
- flag_safety_stop(reason): whenever a stop rule is met.
- call_supervisor(reason): when asked, or when a stop rule requires it.
- draft_estimate(...): only when asked. It goes to the technician's screen. Never say prices aloud.
- ask_senior_expert(question): the company's senior expert, with the company's trade playbook and past
  fixes. Use it for error codes, wiring or control-board diagnosis, symptoms the first checks did not
  explain, anything you are not sure about, and whenever the technician asks for a second opinion.
  Say "Checking with the senior expert." first, then call it with the equipment, symptom and what was
  already checked. Relay its answer briefly and faithfully. It takes a few seconds.

BEFORE LEAVING
- When the technician asks what to verify, give this checklist one item at a time:
{verification}

WHEN THE TECHNICIAN DISAGREES
- Accept it. Ask what they see or measured. Call add_note noting the disagreement.
"""


def _bullets(items: list[str] | None, indent: str = "  ") -> str:
    items = [i for i in (items or []) if isinstance(i, str) and i.strip()]
    if not items:
        return f"{indent}- (none listed)"
    return "\n".join(f"{indent}- {i.strip()}" for i in items)


def _fmt_readings(readings: list[dict[str, Any]] | None) -> str:
    if not readings:
        return "none yet"
    parts = []
    for r in readings[-15:]:
        name = r.get("name", "reading")
        value = r.get("value", "")
        unit = r.get("unit", "") or ""
        parts.append(f"{name} {value} {unit}".strip())
    return "; ".join(parts)


def build_instructions(ctx: dict[str, Any]) -> str:
    """Builds the system instructions from the backend's /agent/visits/:id/context payload."""
    trade = ctx.get("trade") or {}
    job = ctx.get("job") or {}
    company = ctx.get("company") or {}
    tech = ctx.get("tech") or {}
    supervisor = company.get("supervisorName") or "your supervisor"
    co_stop = company.get("coStopPpm")
    co_rule = ""
    if trade.get("id") in ("hvac", "plumbing", "appliance") and co_stop:
        co_rule = f"\n- Ambient CO at or above {co_stop} ppm in living space is a stop rule."
    pre_work = trade.get("preWorkReadings") or []
    pre_work_text = (
        "; ".join(f"{p.get('label')} (when: {p.get('requiredWhen', 'always')})" for p in pre_work if isinstance(p, dict))
        or "none specific to this trade"
    )
    equipment = job.get("equipment") or {}
    equipment_text = ", ".join(f"{k}: {v}" for k, v in equipment.items()) if isinstance(equipment, dict) and equipment else "not recorded"
    return BASE_INSTRUCTIONS.format(
        name=ASSISTANT_NAME,
        company=company.get("name") or "the company",
        trade_name=trade.get("name") or "General field service",
        trade_description=trade.get("description") or "",
        job_title=job.get("title") or "not given",
        complaint=job.get("complaint") or "not given",
        equipment=equipment_text,
        tech_name=tech.get("name") or "the technician",
        certs=", ".join(tech.get("certifications") or []) or "none on file",
        readings=_fmt_readings(ctx.get("readings")),
        pre_work=pre_work_text,
        stop_rules=_bullets(trade.get("stopRules")),
        never_guide=_bullets(trade.get("neverGuide")),
        supervisor=supervisor,
        co_rule=co_rule,
        verification=_bullets(trade.get("verification")),
    )


SAFETY_PROMPT = """You check ONE photo from a {trade} technician's smart glasses for clearly visible hazards.
Return exactly one line: LEVEL | reason
LEVEL is one of: NONE, ADVISORY, CRITICAL.
CRITICAL only when one of these is clearly visible: {critical}; open flame outside a burner; smoke; arcing or sparks.
ADVISORY for: {advisory}.
If the image is blurry, dark, mostly blocked, or you are not sure, return NONE.
Never report gas, carbon monoxide, refrigerant or electrical current. They cannot be seen.
The reason must be under 15 words, plain language."""


def build_safety_prompt(trade: dict[str, Any] | None) -> str:
    trade = trade or {}
    critical = "; ".join(trade.get("visibleHazards") or ["exposed live wiring", "melted insulation", "water on electrical parts"])
    advisory = "; ".join(trade.get("advisories") or ["minor wear worth mentioning"])
    return SAFETY_PROMPT.format(trade=trade.get("name") or "field service", critical=critical, advisory=advisory)


_LEVELS = {"NONE": "none", "ADVISORY": "advisory", "CRITICAL": "critical"}


def parse_safety(text: str | None) -> tuple[str, str]:
    """Parses 'LEVEL | reason'. Anything unexpected is treated as NONE, never as a false alarm."""
    if not text:
        return ("none", "")
    for line in text.strip().splitlines():
        m = re.match(r"^\s*\**\s*(NONE|ADVISORY|CRITICAL)\s*\**\s*(?:\||:|-)\s*(.*)$", line.strip(), re.IGNORECASE)
        if m:
            level = _LEVELS[m.group(1).upper()]
            reason = re.sub(r"\s+", " ", m.group(2)).strip().strip(".")
            if level != "none" and not reason:
                return ("none", "")
            return (level, reason[:140])
        if line.strip().upper() in _LEVELS:
            return ("none", "") if line.strip().upper() != "NONE" else ("none", "")
    return ("none", "")


def clean_transcript(text: str | None) -> str:
    """Gemini sometimes leaks control tokens like <ctrl46> into transcripts."""
    if not text:
        return ""
    return re.sub(r"<ctrl\d+>", "", text).strip()
