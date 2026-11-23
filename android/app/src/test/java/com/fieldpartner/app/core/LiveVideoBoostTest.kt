package com.fieldpartner.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVideoBoostTest {
  @Test fun aiOnlySavesDataAndStillOutpacesTheAiSampler() {
    assertEquals(LiveVideoProfile.AI_ONLY, LiveVideoProfile.forRoom(expertPresent = false))
    assertEquals(5, LiveVideoProfile.AI_ONLY.maxFps)
    assertEquals(1_000_000, LiveVideoProfile.AI_ONLY.maxBitrateBps)
    assertTrue(LiveVideoProfile.AI_ONLY.maxFps >= 1) // agent samples 1 frame a second
  }

  @Test fun expertGetsSmoothVideo() {
    assertEquals(LiveVideoProfile.EXPERT, LiveVideoProfile.forRoom(expertPresent = true))
    assertEquals(15, LiveVideoProfile.EXPERT.maxFps)
    assertEquals(2_500_000, LiveVideoProfile.EXPERT.maxBitrateBps)
  }

  @Test fun expertJoinBoostsAndLeaveDropsBack() {
    val b = LiveVideoBoost()
    b.onPublished(b.wanted)
    assertEquals(LiveVideoProfile.EXPERT, b.setExpertPresent(true))
    assertEquals(null, b.onSwitchDone(LiveVideoProfile.EXPERT))
    assertEquals(LiveVideoProfile.AI_ONLY, b.setExpertPresent(false))
    assertNull(b.onSwitchDone(LiveVideoProfile.AI_ONLY))
    assertEquals(LiveVideoProfile.AI_ONLY, b.published)
  }

  @Test fun repeatedPresenceEventsDoNothing() {
    val b = LiveVideoBoost()
    b.onPublished(b.wanted)
    assertNull(b.setExpertPresent(false))
    assertEquals(LiveVideoProfile.EXPERT, b.setExpertPresent(true))
    // The agent's expert event and the participant join both report the same expert.
    assertNull(b.setExpertPresent(true))
  }

  @Test fun noSwitchBeforeTheTrackIsPublished() {
    val b = LiveVideoBoost()
    assertNull(b.setExpertPresent(true))
    assertEquals(LiveVideoProfile.EXPERT, b.wanted) // first publish uses the boosted profile
  }

  @Test fun neverSwitchesWhileVideoIsMuted() {
    val b = LiveVideoBoost()
    b.onPublished(LiveVideoProfile.AI_ONLY)
    assertNull(b.setMuted(true))
    assertNull(b.setExpertPresent(true))
    // Camera comes back: now the boost applies.
    assertEquals(LiveVideoProfile.EXPERT, b.setMuted(false))
  }

  @Test fun presenceChangeDuringASwitchIsAppliedAfterIt() {
    val b = LiveVideoBoost()
    b.onPublished(LiveVideoProfile.AI_ONLY)
    assertEquals(LiveVideoProfile.EXPERT, b.setExpertPresent(true))
    assertNull(b.setExpertPresent(false)) // expert left while republishing
    assertEquals(LiveVideoProfile.AI_ONLY, b.onSwitchDone(LiveVideoProfile.EXPERT))
  }

  @Test fun failedRepublishLeavesTrackUnpublished() {
    val b = LiveVideoBoost()
    b.onPublished(LiveVideoProfile.AI_ONLY)
    b.setExpertPresent(true)
    assertNull(b.onSwitchDone(null))
    assertNull(b.published)
    assertNull(b.setExpertPresent(false))
  }

  @Test fun resetForgetsEverything() {
    val b = LiveVideoBoost()
    b.onPublished(LiveVideoProfile.EXPERT)
    b.setMuted(true)
    b.reset()
    assertNull(b.published)
    assertEquals(LiveVideoProfile.AI_ONLY, b.wanted)
  }
}
