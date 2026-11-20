import json, os
from prompts import build_instructions, build_safety_prompt, parse_safety, clean_transcript

TRADES = json.load(open(os.path.join(os.path.dirname(__file__), "..", "..", "shared", "trades.json")))["trades"]


def ctx_for(trade_id, **over):
    trade = next(t for t in TRADES if t["id"] == trade_id)
    ctx = {
        "trade": trade,
        "job": {"title": "No heat", "complaint": "Furnace won't light", "equipment": {"brand": "Carrier"}},
        "company": {"name": "Hudson Valley HVAC", "coStopPpm": 35, "supervisorName": "Mitch"},
        "tech": {"name": "Nick", "certifications": ["EPA 608"]},
        "readings": [{"name": "Ambient CO", "value": "0", "unit": "ppm"}],
    }
    ctx.update(over)
    return ctx


def test_every_trade_builds_a_complete_prompt():
    for t in TRADES:
        text = build_instructions(ctx_for(t["id"]))
        assert t["name"] in text
        for rule in t["stopRules"]:
            assert rule in text
        for rule in t["neverGuide"]:
            assert rule in text
        assert "{" not in text.replace("{}", "")  # no unfilled template fields


def test_co_rule_only_for_fuel_trades():
    assert "35 ppm" in build_instructions(ctx_for("hvac"))
    assert "35 ppm" not in build_instructions(ctx_for("roofing"))


def test_missing_context_still_yields_a_safe_prompt():
    text = build_instructions({})
    assert "SAFETY RULES" in text
    assert "your supervisor" in text


def test_prompt_mentions_supervisor_by_name_and_readings():
    text = build_instructions(ctx_for("hvac"))
    assert "Calling Mitch now" in text
    assert "Ambient CO 0 ppm" in text


def test_safety_prompt_uses_trade_hazards():
    p = build_safety_prompt(next(t for t in TRADES if t["id"] == "roofing"))
    assert "overhead power lines" in p
    assert "Never report gas" in p


def test_parse_safety_handles_model_variations():
    assert parse_safety("CRITICAL | Scorched wiring on board.") == ("critical", "Scorched wiring on board")
    assert parse_safety("**ADVISORY** | Filter heavily loaded") == ("advisory", "Filter heavily loaded")
    assert parse_safety("advisory: rusty drain pan") == ("advisory", "rusty drain pan")
    assert parse_safety("NONE | nothing") == ("none", "nothing")
    assert parse_safety("CRITICAL |") == ("none", "")  # a critical with no reason is not trusted
    assert parse_safety("I think there might be an issue") == ("none", "")
    assert parse_safety("") == ("none", "")
    assert parse_safety(None) == ("none", "")
    assert parse_safety("Sure!\nCRITICAL | Water dripping on the control board") == ("critical", "Water dripping on the control board")


def test_clean_transcript_strips_control_tokens():
    assert clean_transcript("<ctrl46>Check the fuse<ctrl47>") == "Check the fuse"
