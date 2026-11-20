package com.fieldpartner.app.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Events the AI agent sends to the phone on the "fp.event" topic. */
sealed class AgentEvent {
  data class AssistantState(val state: String, val reason: String?) : AgentEvent()
  data class Hint(val id: String, val reason: String) : AgentEvent()
  data class Safety(val level: String, val reason: String, val source: String?) : AgentEvent()
  data class Tool(val name: String, val data: Map<String, String>) : AgentEvent()
  data class Escalation(val status: String?, val paged: String?, val reason: String?) : AgentEvent()
  data class Expert(val present: Boolean) : AgentEvent()
  data class Watcher(val status: String) : AgentEvent()
  data class Unknown(val type: String) : AgentEvent()

  companion object {
    /** Parses one fp.event payload. Returns null for anything that is not a JSON object with a type. */
    fun parse(text: String): AgentEvent? {
      val obj = runCatching { FpJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
      val type = obj.str("type") ?: return null
      return when (type) {
        "assistant_state" -> AssistantState(obj.str("state") ?: "unknown", obj.str("reason"))
        "hint" -> Hint(obj.str("id") ?: "", obj.str("reason") ?: "")
        "safety" -> Safety(obj.str("level") ?: "critical", obj.str("reason") ?: "Hazard", obj.str("source"))
        "tool" -> Tool(obj.str("name") ?: "", flatten(obj["data"]))
        "escalation" -> Escalation(obj.str("status"), obj.str("paged"), obj.str("reason"))
        "expert" -> Expert((obj["present"] as? JsonPrimitive)?.booleanOrNull ?: false)
        "watcher" -> Watcher(obj.str("status") ?: "")
        else -> Unknown(type)
      }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun flatten(e: JsonElement?): Map<String, String> {
      val o = e as? JsonObject ?: return emptyMap()
      return o.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }
    }
  }
}

/** Phone to agent commands on the "fp.cmd" topic. Shapes match the iOS app and agent/main.py. */
object AgentCommand {
  fun ask(text: String) = jsonObjectOf(mapOf("type" to "ask", "text" to text)).toString()
  fun listen() = """{"type":"listen"}"""
  fun stop() = """{"type":"stop"}"""
  fun continueLast() = """{"type":"continue"}"""
  fun hintPlay() = """{"type":"hint_play"}"""
  fun camera(allowed: Boolean) = """{"type":"camera","allowed":$allowed}"""
  fun disputed(text: String) = jsonObjectOf(mapOf("type" to "disputed", "text" to text)).toString()
  fun end() = """{"type":"end"}"""
}
