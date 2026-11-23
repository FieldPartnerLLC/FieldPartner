package com.fieldpartner.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesMessagesTest {
  @Test fun metaAiMissing() = assertTrue(GlassesMessages.registrationError("META_AI_NOT_INSTALLED", null).contains("Install the Meta AI app"))

  @Test fun unauthorizedPointsToDeveloperMode() = assertTrue(GlassesMessages.registrationError("UNAUTHORIZED", "x").contains("Developer Mode"))

  @Test fun unknownRegistrationKeepsSdkText() = assertEquals("Connecting to the glasses failed: boom", GlassesMessages.registrationError("NEW_CASE", "boom"))

  @Test fun notRegisteredSession() = assertTrue(GlassesMessages.sessionError("NOT_REGISTERED", null).contains("Connect the glasses first"))

  @Test fun thermalStream() = assertTrue(GlassesMessages.streamError("THERMAL_HOT", null).contains("too hot"))

  @Test fun batteryStream() = assertTrue(GlassesMessages.streamError("BATTERY_LOW", null).contains("battery"))

  @Test fun foldedStream() = assertTrue(GlassesMessages.streamError("HINGE_CLOSED", null).contains("folded"))

  @Test fun unknownStreamKeepsSdkText() = assertEquals("raw", GlassesMessages.streamError("SOMETHING", "raw"))

  @Test fun pausedIsNotAStall() {
    assertTrue(GlassesMessages.isPaused("PAUSED", "STREAMING"))
    assertTrue(GlassesMessages.isPaused("STARTED", "PAUSED"))
    assertFalse(GlassesMessages.isPaused("STARTED", "STREAMING"))
  }

  @Test fun terminalStates() {
    assertTrue(GlassesMessages.isTerminal("STOPPED"))
    assertTrue(GlassesMessages.isTerminal("CLOSED"))
    assertFalse(GlassesMessages.isTerminal("PAUSED"))
    assertFalse(GlassesMessages.isTerminal("STARTED"))
  }
}
