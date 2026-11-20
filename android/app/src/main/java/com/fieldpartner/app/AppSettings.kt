package com.fieldpartner.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Video source for a visit. */
enum class VideoSource(val label: String) {
  GLASSES("Meta glasses"),
  SIMULATED_GLASSES("Simulated glasses (demo video, works in the emulator)"),
  PHONE_CAMERA("Phone camera (no glasses)"),
}

/** User-adjustable settings, stored in the app's private preferences. Mirrors the iOS AppSettings. */
class AppSettings(context: Context) {
  private val p: SharedPreferences = context.getSharedPreferences("fieldpartner", Context.MODE_PRIVATE)

  data class Values(
    val apiBaseUrl: String,
    val wakePhraseOverride: String,
    val aiVoiceVolume: Float,
    val uploadVideoOnCellular: Boolean,
    val language: String,
    val videoSource: VideoSource,
    val simulatedVideoUri: String,
    val showTranscript: Boolean,
    val volumeKeysAsClip: Boolean,
  )

  private val _values = MutableStateFlow(read())
  val values: StateFlow<Values> = _values.asStateFlow()
  val current: Values
    get() = _values.value

  private fun read() =
    Values(
      apiBaseUrl = p.getString("apiBaseUrl", null) ?: BuildConfig.DEFAULT_API_BASE_URL,
      wakePhraseOverride = p.getString("wakePhraseOverride", "") ?: "",
      aiVoiceVolume = p.getFloat("aiVoiceVolume", 0.7f),
      uploadVideoOnCellular = p.getBoolean("uploadVideoOnCellular", false),
      language = p.getString("language", "en-US") ?: "en-US",
      videoSource = runCatching { VideoSource.valueOf(p.getString("videoSource", null) ?: defaultSource().name) }.getOrDefault(defaultSource()),
      simulatedVideoUri = p.getString("simulatedVideoUri", "") ?: "",
      showTranscript = p.getBoolean("showTranscript", true),
      volumeKeysAsClip = p.getBoolean("volumeKeysAsClip", false),
    )

  /** Emulators have no glasses: start them on the simulated feed so the app works out of the box. */
  private fun defaultSource(): VideoSource = if (isEmulator()) VideoSource.SIMULATED_GLASSES else VideoSource.GLASSES

  fun update(block: (Values) -> Values) {
    val v = block(current)
    p.edit()
      .putString("apiBaseUrl", v.apiBaseUrl.trim())
      .putString("wakePhraseOverride", v.wakePhraseOverride.trim())
      .putFloat("aiVoiceVolume", v.aiVoiceVolume)
      .putBoolean("uploadVideoOnCellular", v.uploadVideoOnCellular)
      .putString("language", v.language)
      .putString("videoSource", v.videoSource.name)
      .putString("simulatedVideoUri", v.simulatedVideoUri)
      .putBoolean("showTranscript", v.showTranscript)
      .putBoolean("volumeKeysAsClip", v.volumeKeysAsClip)
      .apply()
    _values.value = v.copy(apiBaseUrl = v.apiBaseUrl.trim(), wakePhraseOverride = v.wakePhraseOverride.trim())
  }

  var token: String?
    get() = p.getString("token", null)
    set(value) {
      p.edit().apply { if (value == null) remove("token") else putString("token", value) }.apply()
    }

  companion object {
    fun isEmulator(): Boolean {
      val f = android.os.Build.FINGERPRINT
      val m = android.os.Build.MODEL
      val h = android.os.Build.HARDWARE
      return f.startsWith("generic") || f.contains("emulator") || m.contains("Emulator") || m.contains("Android SDK built for") ||
        h.contains("ranchu") || h.contains("goldfish") || android.os.Build.PRODUCT.contains("sdk")
    }
  }
}
