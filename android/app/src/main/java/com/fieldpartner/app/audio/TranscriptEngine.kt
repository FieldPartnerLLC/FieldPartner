package com.fieldpartner.app.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.fieldpartner.app.core.Pcm
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Continuous on-device transcript of everything the technician's mic hears.
 *
 * Android lets only one client record at a time, and during a visit WebRTC holds the
 * mic. So instead of letting SpeechRecognizer open the mic, we pipe the call's own
 * audio into it (EXTRA_AUDIO_SOURCE, Android 13+). The transcript drives the wake
 * phrase and the visit record. Nothing here goes to the AI unless the wake phrase is said.
 *
 * Android 12 has no audio-source pipe: voice commands are off there and the tech uses
 * the Talk button, the clip button or typed questions. [available] reports which.
 */
class TranscriptEngine(private val context: Context) : MicBus.Listener {
  enum class Status { OFF, RUNNING, UNAVAILABLE, ERROR }

  var onPartial: ((String) -> Unit)? = null
  var onFinal: ((String) -> Unit)? = null
  var onStatus: ((Status, String?) -> Unit)? = null

  private val main = Handler(Looper.getMainLooper())
  private var recognizer: SpeechRecognizer? = null
  private var language = "en-US"
  @Volatile private var wanted = false
  @Volatile private var pipeOut: OutputStream? = null
  private val queue = ArrayBlockingQueue<ByteArray>(100) // ~10 s of audio; older audio is dropped if the recognizer stalls
  private var writer: Thread? = null
  private var consecutiveErrors = 0
  private var lastPartial = ""

  val available: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isRecognitionAvailable(context)

  fun start(language: String) {
    this.language = language
    if (!available) {
      onStatus?.invoke(Status.UNAVAILABLE, "Voice commands need Android 13+ and a speech service. Use the Talk button.")
      return
    }
    wanted = true
    MicBus.add(this)
    startWriter()
    main.post { startSession() }
  }

  fun stop() {
    wanted = false
    MicBus.remove(this)
    main.post {
      recognizer?.let {
        runCatching { it.cancel() }
        runCatching { it.destroy() }
      }
      recognizer = null
    }
    closePipe()
    writer?.interrupt()
    writer = null
    queue.clear()
    onStatus?.invoke(Status.OFF, null)
  }

  override fun onPcm(chunk: PcmChunk) {
    if (!wanted) return
    val mono = Pcm.toMono16(chunk.data, chunk.sampleRate, chunk.channels, SAMPLE_RATE)
    if (!queue.offer(mono)) {
      queue.poll()
      queue.offer(mono)
    }
  }

  private fun startWriter() {
    if (writer != null) return
    writer =
      Thread({
          while (wanted) {
            val buf =
              try {
                queue.poll(500, TimeUnit.MILLISECONDS)
              } catch (_: InterruptedException) {
                break
              } ?: continue
            val out = pipeOut ?: continue
            try {
              out.write(buf)
            } catch (_: IOException) {
              // Recognizer closed its end; the session restarts with a new pipe.
              pipeOut = null
            }
          }
        }, "fp-asr-writer")
        .also { it.start() }
  }

  private fun closePipe() {
    runCatching { pipeOut?.close() }
    pipeOut = null
  }

  private fun startSession() {
    if (!wanted) return
    closePipe()
    recognizer?.let { runCatching { it.destroy() } }
    val rec =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
      } else {
        SpeechRecognizer.createSpeechRecognizer(context)
      }
    recognizer = rec
    val pipe = ParcelFileDescriptor.createPipe()
    pipeOut = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
        // One long session that reports each spoken segment, instead of stopping after one sentence.
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
      }
    rec.setRecognitionListener(listener)
    try {
      rec.startListening(intent)
      onStatus?.invoke(Status.RUNNING, null)
    } catch (e: Exception) {
      Log.w(TAG, "startListening failed: ${e.message}")
      scheduleRestart(error = true)
    }
    runCatching { pipe[0].close() } // the recognizer holds its own dup
  }

  private fun scheduleRestart(error: Boolean) {
    if (!wanted) return
    if (error) consecutiveErrors++ else consecutiveErrors = 0
    if (consecutiveErrors >= 8) {
      onStatus?.invoke(Status.ERROR, "Speech recognition keeps failing. Voice commands paused; use the Talk button.")
      wanted = false
      return
    }
    val wait = if (error) minOf(10_000L, 500L * (1 shl minOf(consecutiveErrors, 5))) else 100L
    main.postDelayed({ startSession() }, wait)
  }

  private fun emitFinal(results: Bundle?) {
    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
    lastPartial = ""
    if (text.isNotEmpty()) onFinal?.invoke(text)
  }

  private val listener =
    object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {}

      override fun onBeginningOfSpeech() {}

      override fun onRmsChanged(rmsdB: Float) {}

      override fun onBufferReceived(buffer: ByteArray?) {}

      override fun onEndOfSpeech() {}

      override fun onEvent(eventType: Int, params: Bundle?) {}

      override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (text.isNotEmpty() && text != lastPartial) {
          lastPartial = text
          onPartial?.invoke(text)
        }
      }

      override fun onResults(results: Bundle?) {
        emitFinal(results)
        scheduleRestart(error = false)
      }

      override fun onSegmentResults(segmentResults: Bundle) {
        consecutiveErrors = 0
        emitFinal(segmentResults)
      }

      override fun onEndOfSegmentedSession() {
        scheduleRestart(error = false)
      }

      override fun onError(error: Int) {
        // No match and silence timeouts are normal in a quiet basement: restart quietly.
        val benign = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (!benign) Log.w(TAG, "recognizer error $error")
        scheduleRestart(error = !benign)
      }
    }

  companion object {
    private const val TAG = "TranscriptEngine"
    const val SAMPLE_RATE = 16_000
  }
}
