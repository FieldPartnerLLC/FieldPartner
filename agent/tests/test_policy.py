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
