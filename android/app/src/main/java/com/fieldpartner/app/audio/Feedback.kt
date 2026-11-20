package com.fieldpartner.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Short sounds so the tech knows what happened without looking at the phone. */
enum class Earcon(val tone: Int, val ms: Int) {
  WAKE(ToneGenerator.TONE_PROP_BEEP, 120),
  CLOSE(ToneGenerator.TONE_PROP_ACK, 120),
  HINT(ToneGenerator.TONE_PROP_PROMPT, 150),
  ALERT(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 900),
  RECORDING_ON(ToneGenerator.TONE_PROP_BEEP2, 200),
  RECORDING_OFF(ToneGenerator.TONE_PROP_NACK, 200),
}

/** Earcons, haptics and short spoken status lines. One per app. */
class Feedback(private val context: Context) {
  private var tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }.getOrNull()
  private var tts: TextToSpeech? = null
  private var ttsReady = false

  init {
    tts =
      TextToSpeech(context) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
          tts?.language = Locale.US
          tts?.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
          )
        }
      }
  }

  fun play(e: Earcon) {
    runCatching { tone?.startTone(e.tone, e.ms) }
  }

  fun buzz(strong: Boolean = false) {
    val v =
      if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
      else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    v?.vibrate(VibrationEffect.createOneShot(if (strong) 600 else 80, VibrationEffect.DEFAULT_AMPLITUDE))
  }

  fun say(text: String, volume: Float = 0.8f) {
    if (!ttsReady) return
    val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f)) }
    tts?.speak(text, TextToSpeech.QUEUE_ADD, params, "fp-${System.nanoTime()}")
  }

  fun release() {
    tone?.release()
    tone = null
    tts?.shutdown()
    tts = null
  }
}
