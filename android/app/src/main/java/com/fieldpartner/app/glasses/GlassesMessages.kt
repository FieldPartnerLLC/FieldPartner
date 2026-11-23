package com.fieldpartner.app.glasses

/**
 * Plain-language text for Meta Wearables toolkit errors and states, keyed by the SDK enum name
 * (RegistrationError, DeviceSessionError, StreamError, StreamState, DeviceSessionState).
 * No Android or SDK imports, so it runs in JVM unit tests. Unknown names fall back to the SDK text.
 */
object GlassesMessages {
  private const val DEV_MODE =
    "In the Meta AI app open Settings > App Info, tap the app version 5 times, then turn on Developer Mode."

  fun registrationError(name: String, fallback: String?): String =
    when (name) {
      "META_AI_NOT_INSTALLED" -> "Install the Meta AI app and pair your glasses in it first."
      "UNAUTHORIZED" -> "Meta AI did not allow FieldPartner. $DEV_MODE"
      "ALREADY_REGISTERED" -> "FieldPartner is already connected to your glasses."
      "FAILED_TO_REGISTER" -> "Connecting to the glasses failed. Open Meta AI, check the glasses are paired and charged, then try again."
      else -> "Connecting to the glasses failed: ${fallback ?: name}"
    }

  fun sessionError(name: String, fallback: String?): String =
    when (name) {
      "NOT_REGISTERED" -> "Connect the glasses first: Settings > Connect Meta glasses."
      "NO_ELIGIBLE_DEVICE" -> "No glasses found. Turn them on, unfold them and keep them near the phone."
      "DEVICE_DISCONNECTED", "SESSION_ENDED_BY_DEVICE" -> "The glasses disconnected. Reconnecting."
      "THERMAL_CRITICAL", "THERMAL_EMERGENCY" -> "Glasses are too hot. Video is paused until they cool down."
      "BATTERY_CRITICAL" -> "Glasses battery is critically low. Charge them."
      "PEAK_POWER_SHUTDOWN" -> "Glasses hit their power limit. Video is paused for a moment."
      "DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED", "INSUFFICIENT_SDK_VERSION" -> "Update the glasses software in the Meta AI app, then try again."
      "CAPABILITY_DENIED" -> "Camera access was denied. Allow FieldPartner to use the glasses camera in Meta AI."
      else -> fallback ?: name
    }

  fun streamError(name: String, fallback: String?): String =
    when (name) {
      "THERMAL_HOT" -> "Glasses are too hot. Video is paused until they cool down."
      "BATTERY_LOW" -> "Glasses battery is low. Video is paused."
      "PEAK_POWER_LIMIT" -> "Glasses hit their power limit. Video is paused for a moment."
      "HINGE_CLOSED" -> "The glasses are folded. Unfold them to resume video."
      "PERMISSIONS_DENIED" -> "Camera access was denied. Allow FieldPartner to use the glasses camera in Meta AI."
      "TIMEOUT" -> "The glasses camera did not respond. Retrying."
      else -> fallback ?: name
    }

  /** Paused means the toolkit stopped frames on purpose (glasses off the face, folded) and resumes by itself. */
  fun isPaused(sessionState: String, streamState: String): Boolean = sessionState == "PAUSED" || streamState == "PAUSED"

  /** A session or stream in one of these states will not deliver frames again and must be replaced. */
  fun isTerminal(state: String): Boolean = state == "STOPPED" || state == "CLOSED"
}
