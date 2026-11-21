"""Timing behavior in the field: the AI must stay out of the customer conversation."""
from policy import AssistantGate, GatedSampler, SafetyDeduper, WatcherBudget


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def test_silent_and_blind_until_called():
    c = Clock()
    g = AssistantGate(clock=c)
    s = GatedSampler(g, clock=c)
    assert not g.active
    assert s(None, None) is False  # no frames to the model before the wake word


def test_one_frame_per_second_while_active():
    c = Clock()
    g = AssistantGate(clock=c)
    s = GatedSampler(g, clock=c)
    g.activate()
    assert s(None, None) is True
    c.t += 0.4
    assert s(None, None) is False
    c.t += 0.7
    assert s(None, None) is True


def test_wake_forces_a_fresh_frame_immediately():
    c = Clock()
    g = AssistantGate(clock=c)
    s = GatedSampler(g, clock=c)
    g.activate()
    s(None, None)
    c.t += 0.1
    s.force_next = True
    assert s(None, None) is True


def test_follow_up_window_closes_after_silence():
    c = Clock()
    g = AssistantGate(follow_up_seconds=20, clock=c)
    g.activate()
    g.on_agent_speaking(True)
    c.t += 30
    assert not g.should_close()  # still talking
    g.on_agent_speaking(False)
    c.t += 19
    assert not g.should_close()
    c.t += 2
    assert g.should_close()


def test_tech_follow_up_extends_window():
    c = Clock()
    g = AssistantGate(follow_up_seconds=20, clock=c)
    g.activate()
    c.t += 15
    g.on_user_speaking(True)
    c.t += 10
    g.on_user_speaking(False)
    c.t += 15
    assert not g.should_close()
    c.t += 6
    assert g.should_close()


def test_runaway_session_is_capped():
    c = Clock()
    g = AssistantGate(follow_up_seconds=20, max_active_seconds=180, clock=c)
    g.activate()
    for _ in range(40):
        c.t += 5
        g.on_user_speaking(True)
        g.on_user_speaking(False)
    assert g.should_close()


def test_no_frames_while_camera_paused_or_expert_live():
    c = Clock()
    g = AssistantGate(clock=c)
    s = GatedSampler(g, clock=c)
    g.activate()
    g.camera_allowed = False
    assert s(None, None) is False
    g.camera_allowed = True
    g.expert_live = True
    assert s(None, None) is False


def test_same_hazard_not_repeated_every_check():
    c = Clock()
    d = SafetyDeduper(repeat_after_seconds=120, clock=c)
    assert d.should_report("critical", "Scorched wiring on control board")
    c.t += 5
    assert not d.should_report("critical", "scorched wiring on the control board")
    c.t += 130
    assert d.should_report("critical", "Scorched wiring on control board")
    assert not d.should_report("none", "anything")


def test_watcher_skips_stale_frames_paused_camera_and_respects_interval():
    c = Clock()
    b = WatcherBudget(interval_seconds=5, max_frame_age_seconds=3, clock=c)
    assert not b.due(frame_arrived_at=c.t - 10, camera_allowed=True)  # glasses off: stale frame
    assert not b.due(frame_arrived_at=c.t, camera_allowed=False)  # camera paused
    assert b.due(frame_arrived_at=c.t, camera_allowed=True)
    c.t += 2
    assert not b.due(frame_arrived_at=c.t, camera_allowed=True)
    c.t += 4
    assert b.due(frame_arrived_at=c.t, camera_allowed=True)


# ---------------------------------------------------------------- cost controls

from policy import SceneGate, UsageMeter, thumb_diff  # noqa: E402


class FakeClock:
    def __init__(self) -> None:
        self.t = 1000.0

    def __call__(self) -> float:
        return self.t


def test_scene_gate_skips_an_unchanged_view_and_rechecks_it_later():
    clock = FakeClock()
    gate = SceneGate(clock=clock, static_recheck_seconds=30)
    panel = bytes([100] * 256)
    assert gate.should_check(panel)  # first look
    clock.t += 5
    assert not gate.should_check(bytes([102] * 256))  # lighting flicker, same panel
    clock.t += 5
    assert not gate.should_check(panel)
    clock.t += 25
    assert gate.should_check(panel)  # 30 s passed, look again anyway
    assert gate.skipped == 2


def test_scene_gate_checks_at_once_when_the_view_changes():
    clock = FakeClock()
    gate = SceneGate(clock=clock)
    assert gate.should_check(bytes([100] * 256))
    clock.t += 1
    assert gate.should_check(bytes([30] * 128 + [200] * 128))


def test_thumb_diff_handles_missing_and_mismatched_thumbs():
    assert thumb_diff(None, b"\x00") == 255.0
    assert thumb_diff(b"\x00\x00", b"\x00") == 255.0
    assert thumb_diff(b"\x0a\x14", b"\x0a\x0a") == 5.0


def test_usage_meter_totals_takes_and_restores():
    m = UsageMeter()
    m.add("gemini-3.1-flash-live-preview", "live", input_tokens=1000, audio_in_tokens=400, image_in_tokens=300, output_tokens=200, audio_out_tokens=200)
    m.add("gemini-3.1-flash-live-preview", "live", input_tokens=500)
    m.add("gemini-3.5-flash-lite", "safety_watcher", input_tokens=120, output_tokens=5)
    items = m.take()
    live = next(i for i in items if i["task"] == "live")
    assert live["inputTokens"] == 1500 and live["audioOutTokens"] == 200
    assert m.take() == []
    m.restore(items)
    assert len(m.take()) == 2
