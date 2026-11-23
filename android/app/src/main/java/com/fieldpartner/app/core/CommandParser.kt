package com.fieldpartner.app.core

/** What the technician said, once it is addressed to the assistant. */
sealed class VoiceCommand {
  /** Wake phrase + question. Empty text means the tech is still talking: open the mic. */
  data class Ask(val text: String) : VoiceCommand()
  data object Stop : VoiceCommand()
  data object ContinueLast : VoiceCommand()
  data object PauseCamera : VoiceCommand()
  data object ResumeCamera : VoiceCommand()
  data object PlayHint : VoiceCommand()
  data object Acknowledge : VoiceCommand()
  data object CallExpert : VoiceCommand()
  data object StopRecording : VoiceCommand()
  data class Disputed(val text: String) : VoiceCommand()
}

/**
 * Turns the running on-device transcript into commands. Pure logic, unit tested.
 * Port of the iOS CommandParser: the same phrases behave the same on both phones.
 *
 * The wake phrase starts a question ("hey dispatch, what's this error code?").
 * Short commands work with the assistant's name alone ("partner, stop") or right
 * after the wake phrase ("hey dispatch, pause camera"). Nothing un-addressed ever
 * triggers a command, so a customer saying "stop" stops nothing.
 */
class CommandParser(val wakePhrase: String, val assistantName: String = "partner") {

  /** Recognizers hear "hey dispatch" in many ways. These variants all count. Longest first. */
  val wakeVariants: List<String> by lazy {
    val base = normalize(wakePhrase)
    val v = linkedSetOf(base)
    if (base.startsWith("hey ")) {
      val rest = base.removePrefix("hey ")
      listOf("hi", "hay").forEach { v.add("$it $rest") }
    }
    if (base.contains("dispatch")) {
      v.add(base.replace("dispatch", "dispatcher"))
      v.add(base.replace("dispatch", "this patch"))
    }
    v.filter { it.isNotBlank() }.sortedByDescending { it.length }
  }

  /**
   * Mishearings of "hey" that are also everyday words ("we sent a dispatch", "okay, dispatch
   * will call"). They only count at the very start of an utterance, so talk about a dispatch
   * never wakes the assistant. Same rule as iOS.
   */
  val startOnlyWakeVariants: List<String> by lazy {
    val base = normalize(wakePhrase)
    if (!base.startsWith("hey ")) return@lazy emptyList()
    val rest = base.removePrefix("hey ")
    if (rest.isBlank()) emptyList() else listOf("a $rest", "ok $rest", "okay $rest")
  }

  fun normalize(s: String): String {
    val cleaned = s.lowercase().map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }.joinToString("")
    return cleaned.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
  }

  /**
   * Finds the wake phrase in an utterance. Returns the text after it, or null if absent.
   * The phrase must sit on word boundaries on both sides ("hey max" never matches "they maximum"
   * or "hey maxwell"), and every occurrence is checked, not just the first.
   */
  fun textAfterWake(utterance: String): String? {
    val n = normalize(utterance)
    val candidates = (wakeVariants.map { it to false } + startOnlyWakeVariants.map { it to true })
      .sortedByDescending { it.first.length }
    for ((variant, startOnly) in candidates) {
      var from = 0
      while (from < n.length) {
        val idx = n.indexOf(variant, from)
        if (idx < 0) break
        val startOk = idx == 0 || n[idx - 1] == ' '
        val end = idx + variant.length
        val endOk = end == n.length || n[end] == ' '
        if (startOk && endOk && (!startOnly || idx == 0)) return n.substring(end).trim()
        from = idx + 1
      }
    }
    return null
  }

  private val table: List<Pair<String, VoiceCommand>> =
    listOf(
      "stop recording" to VoiceCommand.StopRecording,
      "stop" to VoiceCommand.Stop,
      "quiet" to VoiceCommand.Stop,
      "hold on" to VoiceCommand.Stop,
      "continue" to VoiceCommand.ContinueLast,
      "go on" to VoiceCommand.ContinueLast,
      "repeat" to VoiceCommand.ContinueLast,
      "pause camera" to VoiceCommand.PauseCamera,
      "camera off" to VoiceCommand.PauseCamera,
      "resume camera" to VoiceCommand.ResumeCamera,
      "camera on" to VoiceCommand.ResumeCamera,
      "go ahead" to VoiceCommand.PlayHint,
      "acknowledged" to VoiceCommand.Acknowledge,
      "acknowledge" to VoiceCommand.Acknowledge,
      "call expert" to VoiceCommand.CallExpert,
      "call supervisor" to VoiceCommand.CallExpert,
      "get help" to VoiceCommand.CallExpert,
    )

  /** Short commands. Only called on text that was addressed to the assistant. */
  fun shortCommand(text: String): VoiceCommand? {
    val t = normalize(text)
    for ((phrase, cmd) in table) {
      if (t == phrase || t.startsWith("$phrase ")) return cmd
    }
    if (t.startsWith("call ") && t.split(' ').size <= 3) return VoiceCommand.CallExpert
    if (t.startsWith("that s wrong") || t.startsWith("thats wrong") || t.startsWith("i disagree")) return VoiceCommand.Disputed(t)
    return null
  }

  /** Interprets one finished utterance from the transcript. */
  fun parse(finalUtterance: String): VoiceCommand? {
    textAfterWake(finalUtterance)?.let { rest ->
      return shortCommand(rest) ?: VoiceCommand.Ask(rest)
    }
    val n = normalize(finalUtterance)
    val name = normalize(assistantName)
    if (name.isNotEmpty() && (n == name || n.startsWith("$name "))) {
      return shortCommand(n.removePrefix(name).trim())
    }
    return null
  }

  /** Early wake detection on partial results, so the chime plays while the tech is still talking. */
  fun wakeHeard(partial: String): Boolean = textAfterWake(partial) != null
}
