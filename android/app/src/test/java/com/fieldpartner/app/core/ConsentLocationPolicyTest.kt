package com.fieldpartner.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentLocationPolicyTest {
  private val now = 1_800_000_000_000L

  @Test
  fun recentFixIsFresh() {
    assertTrue(ConsentLocationPolicy.isFresh(now - 30_000, now))
    assertTrue(ConsentLocationPolicy.isFresh(now - ConsentLocationPolicy.MAX_FIX_AGE_MS, now))
  }

  @Test
  fun staleMissingOrFutureFixIsNotFresh() {
    assertFalse(ConsentLocationPolicy.isFresh(now - ConsentLocationPolicy.MAX_FIX_AGE_MS - 1, now))
    assertFalse(ConsentLocationPolicy.isFresh(null, now))
    assertFalse(ConsentLocationPolicy.isFresh(now + 5_000, now))
  }

  @Test
  fun picksNewestFreshCandidate() {
    val picked = ConsentLocationPolicy.pick(listOf("gps" to now - 120_000, "network" to now - 20_000, "old" to now - 3_600_000), now)
    assertEquals("network", picked)
  }

  @Test
  fun picksNothingWhenAllStale() {
    assertNull(ConsentLocationPolicy.pick(listOf("old" to now - 3_600_000), now))
    assertNull(ConsentLocationPolicy.pick(emptyList<Pair<String, Long>>(), now))
  }

  @Test
  fun waitStaysShort() {
    assertTrue(ConsentLocationPolicy.MAX_WAIT_MS <= 5_000L)
  }
}
