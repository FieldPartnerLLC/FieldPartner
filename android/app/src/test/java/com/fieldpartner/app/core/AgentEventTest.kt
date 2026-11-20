package com.fieldpartner.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payloads exactly as agent/main.py sends them on fp.event. */
class AgentEventTest {
  @Test fun assistantState() =
    assertEquals(AgentEvent.AssistantState("idle", "follow_up_window_closed"), AgentEvent.parse("""{"type":"assistant_state","state":"idle","reason":"follow_up_window_closed"}"""))

  @Test fun toolWithMixedTypes() {
    val e = AgentEvent.parse("""{"type":"tool","name":"save_reading","data":{"name":"CO ambient","value":12,"unit":"ppm","ok":true}}""") as AgentEvent.Tool
    assertEquals("save_reading", e.name)
    assertEquals("12", e.data["value"])
    assertEquals("true", e.data["ok"])
  }

  @Test fun safetyFromWatcher() =
    assertEquals(AgentEvent.Safety("critical", "Exposed live conductor", "watcher"), AgentEvent.parse("""{"type":"safety","level":"critical","reason":"Exposed live conductor","source":"watcher"}"""))

  @Test fun hint() = assertEquals(AgentEvent.Hint("h1", "Hose looks kinked"), AgentEvent.parse("""{"type":"hint","id":"h1","reason":"Hose looks kinked"}"""))

  @Test fun escalationWithNullPaged() =
    assertEquals(AgentEvent.Escalation("failed", null, "x"), AgentEvent.parse("""{"type":"escalation","status":"failed","paged":null,"reason":"x"}"""))

  @Test fun expert() = assertEquals(AgentEvent.Expert(true), AgentEvent.parse("""{"type":"expert","present":true}"""))

  @Test fun garbageIsIgnored() {
    assertNull(AgentEvent.parse("not json"))
    assertNull(AgentEvent.parse("[1,2]"))
    assertNull(AgentEvent.parse("""{"no":"type"}"""))
    assertTrue(AgentEvent.parse("""{"type":"future_thing"}""") is AgentEvent.Unknown)
  }

  @Test fun commandsAreValidJsonWithEscaping() {
    val ask = AgentCommand.ask("what's the \"limit\" switch?\nline two")
    val obj = FpJson.parseToJsonElement(ask).toString()
    assertTrue(obj.contains("\"type\":\"ask\""))
    assertEquals("""{"type":"camera","allowed":false}""", AgentCommand.camera(false))
    FpJson.parseToJsonElement(AgentCommand.disputed("that's \\ wrong"))
  }
}
