"""Timing and gating rules for the assistant. Pure logic with an injectable clock, unit tested."""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Callable


@dataclass
class AssistantGate:
    """
    The assistant is silent and deaf by default. It becomes active when the technician
    says the wake phrase or presses the button, and stays active through a follow-up
    window after it finishes speaking. Video frames go to the model only while active.
    """

    follow_up_seconds: float = 20.0
    max_active_seconds: float = 180.0
    clock: Callable[[], float] = time.monotonic

    active: bool = False
    camera_allowed: bool = True
    expert_live: bool = False
    _activated_at: float = 0.0
    _last_activity: float = 0.0
    _agent_speaking: bool = False
    _user_speaking: bool = False

    def activate(self) -> None:
        now = self.clock()
        self.active = True
        self._activated_at = now
        self._last_activity = now

    def deactivate(self) -> None:
        self.active = False
        self._agent_speaking = False
        self._user_speaking = False

    def on_agent_speaking(self, speaking: bool) -> None:
        self._agent_speaking = speaking
        self._last_activity = self.clock()

    def on_user_speaking(self, speaking: bool) -> None:
        self._user_speaking = speaking
        if self.active:
            self._last_activity = self.clock()

    def should_close(self) -> bool:
        """True when the follow-up window has passed with nobody talking."""
        if not self.active:
            return False
        now = self.clock()
        if now - self._activated_at > self.max_active_seconds and not self._agent_speaking:
            return True
        if self._agent_speaking or self._user_speaking:
            return False
        return now - self._last_activity > self.follow_up_seconds

    def frames_to_model(self) -> bool:
        return self.active and self.camera_allowed and not self.expert_live


@dataclass
class GatedSampler:
    """Video sampler for AgentSession: 1 frame/second while active, none otherwise."""

    gate: AssistantGate
    fps: float = 1.0
    clock: Callable[[], float] = time.monotonic
    _last: float | None = None
    force_next: bool = False

    def __call__(self, frame, session) -> bool:  # noqa: ANN001 - LiveKit types
        if not self.gate.frames_to_model():
            return False
        now = self.clock()
        if self.force_next or self._last is None or now - self._last >= 1.0 / self.fps:
            self._last = now
            self.force_next = False
            return True
        return False


@dataclass
class SafetyDeduper:
    """Stops the watcher from repeating the same warning every 5 seconds."""

    repeat_after_seconds: float = 120.0
    clock: Callable[[], float] = time.monotonic
    _seen: dict[str, float] = field(default_factory=dict)

    @staticmethod
    def _key(level: str, reason: str) -> str:
        words = sorted(set(w for w in reason.lower().split() if len(w) > 3))
        return level + ":" + " ".join(words[:6])

    def should_report(self, level: str, reason: str) -> bool:
        if level == "none":
            return False
        key = self._key(level, reason)
        now = self.clock()
        last = self._seen.get(key)
        if last is not None and now - last < self.repeat_after_seconds:
            return False
        self._seen[key] = now
        return True


@dataclass
class WatcherBudget:
    """
    Caps how often frames go to the hazard model. Skips checks while the camera is
    paused, the frame is stale (glasses off or folded), or the same scene repeats.
    """

    interval_seconds: float = 5.0
    max_frame_age_seconds: float = 3.0
    clock: Callable[[], float] = time.monotonic
    _last_check: float = 0.0

    def due(self, frame_arrived_at: float, camera_allowed: bool) -> bool:
        now = self.clock()
        if not camera_allowed:
            return False
        if frame_arrived_at <= 0 or now - frame_arrived_at > self.max_frame_age_seconds:
            return False
        if now - self._last_check < self.interval_seconds:
            return False
        self._last_check = now
        return True


def thumb_diff(a: bytes | None, b: bytes | None) -> float:
    """Mean absolute difference (0-255) between two same-size grayscale thumbnails."""
    if not a or not b or len(a) != len(b):
        return 255.0
    return sum(abs(x - y) for x, y in zip(a, b)) / len(a)


@dataclass
class SceneGate:
    """
    Skips hazard checks while the camera sees the same thing. A tech staring at one panel for
    two minutes does not need 24 identical checks. A changed scene is checked at once (subject to
    the WatcherBudget interval); an unchanged one at most every `static_recheck_seconds`.
    """

    change_threshold: float = 8.0  # on 0-255; lighting flicker stays under about 4
    static_recheck_seconds: float = 30.0
    clock: Callable[[], float] = time.monotonic
    _last_thumb: bytes | None = None
    _last_check: float = 0.0
    skipped: int = 0

    def should_check(self, thumb: bytes) -> bool:
        now = self.clock()
        changed = thumb_diff(thumb, self._last_thumb) >= self.change_threshold
        if changed or now - self._last_check >= self.static_recheck_seconds:
            self._last_thumb = thumb
            self._last_check = now
            return True
        self.skipped += 1
        return False


@dataclass
class UsageMeter:
    """Adds up tokens per (model, task) so the agent can report the Gemini bill to the backend."""

    _rows: dict[tuple[str, str], dict[str, int]] = field(default_factory=dict)

    def add(
        self,
        model: str,
        task: str,
        *,
        input_tokens: int = 0,
        cached_tokens: int = 0,
        audio_in_tokens: int = 0,
        image_in_tokens: int = 0,
        output_tokens: int = 0,
        audio_out_tokens: int = 0,
    ) -> None:
        row = self._rows.setdefault(
            (model, task),
            {"inputTokens": 0, "cachedTokens": 0, "audioInTokens": 0, "imageInTokens": 0, "outputTokens": 0, "audioOutTokens": 0},
        )
        row["inputTokens"] += max(0, int(input_tokens or 0))
        row["cachedTokens"] += max(0, int(cached_tokens or 0))
        row["audioInTokens"] += max(0, int(audio_in_tokens or 0))
        row["imageInTokens"] += max(0, int(image_in_tokens or 0))
        row["outputTokens"] += max(0, int(output_tokens or 0))
        row["audioOutTokens"] += max(0, int(audio_out_tokens or 0))

    def take(self) -> list[dict]:
        """Returns the totals since the last take and resets."""
        items = [{"model": m, "task": t, **v} for (m, t), v in self._rows.items() if any(v.values())]
        self._rows = {}
        return items

    def restore(self, items: list[dict]) -> None:
        """Puts items back after a failed upload so nothing is lost."""
        for it in items:
            self.add(
                it["model"],
                it["task"],
                input_tokens=it["inputTokens"],
                cached_tokens=it["cachedTokens"],
                audio_in_tokens=it["audioInTokens"],
                image_in_tokens=it["imageInTokens"],
                output_tokens=it["outputTokens"],
                audio_out_tokens=it["audioOutTokens"],
            )
