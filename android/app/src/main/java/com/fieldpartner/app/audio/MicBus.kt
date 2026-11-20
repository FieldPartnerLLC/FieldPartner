package com.fieldpartner.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/** One chunk of 16-bit little-endian PCM from the microphone. */
class PcmChunk(val data: ByteArray, val sampleRate: Int, val channels: Int)

/**
 * Fans the microphone out to the transcript engine and the audio recorder.
 *
 * While the live call is up, WebRTC owns the mic and hands us its samples through
 * [JavaAudioDeviceModule.SamplesReadyCallback] (same audio the AI and the expert hear,
 * after echo cancellation). While offline, [LocalMic] reads the mic directly. Only one
 * source runs at a time: Android gives the mic to one app client at a time.
 */
object MicBus : JavaAudioDeviceModule.SamplesReadyCallback {
  fun interface Listener {
    fun onPcm(chunk: PcmChunk)
  }

  private val listeners = CopyOnWriteArrayList<Listener>()

  @Volatile var lastChunkAtMs: Long = 0
    private set

  fun add(l: Listener) {
    listeners.addIfAbsent(l)
  }

  fun remove(l: Listener) {
    listeners.remove(l)
  }

  /** Called on WebRTC's audio thread. Must return quickly: listeners only queue the bytes. */
  override fun onWebRtcAudioRecordSamplesReady(samples: JavaAudioDeviceModule.AudioSamples) {
    if (samples.audioFormat != AudioFormat.ENCODING_PCM_16BIT) return
    publish(PcmChunk(samples.data, samples.sampleRate, samples.channelCount))
  }

  fun publish(chunk: PcmChunk) {
    lastChunkAtMs = System.currentTimeMillis()
    for (l in listeners) {
      try {
        l.onPcm(chunk)
      } catch (e: Exception) {
        Log.w("MicBus", "listener failed: ${e.message}")
      }
    }
  }
}

/** Direct microphone capture for when the live call is down. Feeds [MicBus]. */
class LocalMic(private val context: Context) {
  @Volatile private var thread: Thread? = null
  @Volatile private var running = false

  val isRunning: Boolean
    get() = running

  @SuppressLint("MissingPermission")
  fun start(): Boolean {
    if (running) return true
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
    val rate = 16_000
    val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) return false
    val rec =
      try {
        AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
      } catch (e: Exception) {
        Log.w("LocalMic", "AudioRecord failed: ${e.message}")
        return false
      }
    if (rec.state != AudioRecord.STATE_INITIALIZED) {
      rec.release()
      return false
    }
    running = true
    thread =
      Thread({
          val buf = ByteArray(rate / 10 * 2) // 100 ms
          try {
            rec.startRecording()
            while (running) {
              val n = rec.read(buf, 0, buf.size)
              if (n > 0) MicBus.publish(PcmChunk(buf.copyOf(n), rate, 1))
              else if (n < 0) break
            }
          } catch (e: Exception) {
            Log.w("LocalMic", "capture stopped: ${e.message}")
          } finally {
            runCatching { rec.stop() }
            rec.release()
            running = false
          }
        }, "fp-local-mic")
        .also { it.start() }
    return true
  }

  fun stop() {
    running = false
    thread?.join(500)
    thread = null
  }
}
