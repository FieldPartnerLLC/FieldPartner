package com.fieldpartner.app.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.fieldpartner.app.audio.MicBus
import com.fieldpartner.app.audio.PcmChunk
import com.fieldpartner.app.core.Pcm
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Records the visit audio to AAC .m4a files, one file per [segmentMs] so a crash or a
 * dead battery loses at most one segment. Finished segments go to [onSegmentFinished]
 * for upload. Runs on its own thread; the mic callback only queues bytes.
 */
class AudioSegmentRecorder(private val dir: File, private val prefix: String, private val segmentMs: Long = 5 * 60_000L) : MicBus.Listener {
  var onSegmentFinished: ((File) -> Unit)? = null

  private val queue = LinkedBlockingQueue<ByteArray>(600)
  @Volatile private var running = false
  private var thread: Thread? = null

  val isRecording: Boolean
    get() = running

  fun start() {
    if (running) return
    dir.mkdirs()
    running = true
    MicBus.add(this)
    thread = Thread({ loop() }, "fp-audio-rec").also { it.start() }
  }

  fun stop() {
    if (!running) return
    running = false
    MicBus.remove(this)
    thread?.join(3000)
    thread = null
    queue.clear()
  }

  override fun onPcm(chunk: PcmChunk) {
    if (!running) return
    queue.offer(Pcm.toMono16(chunk.data, chunk.sampleRate, chunk.channels, RATE))
  }

  private class Segment(val file: File, val codec: MediaCodec, val muxer: MediaMuxer, val startedAt: Long) {
    var track = -1
    var muxing = false
    var samplesIn = 0L
  }

  private fun open(): Segment? =
    try {
      val file = File(dir, "${prefix}-audio-${System.currentTimeMillis()}.m4a")
      val fmt =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
          setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
          setInteger(MediaFormat.KEY_BIT_RATE, 48_000)
          setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
      val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
      codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      codec.start()
      Segment(file, codec, MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), System.currentTimeMillis())
    } catch (e: Exception) {
      Log.e(TAG, "cannot open audio segment: ${e.message}")
      null
    }

  private fun loop() {
    var seg = open() ?: run { running = false; return }
    val info = MediaCodec.BufferInfo()
    while (running || queue.isNotEmpty()) {
      val pcm = queue.poll(200, TimeUnit.MILLISECONDS)
      if (pcm != null) feed(seg, pcm)
      drain(seg, info, endOfStream = false)
      if (System.currentTimeMillis() - seg.startedAt >= segmentMs) {
        close(seg, info)
        seg = open() ?: run { running = false; return }
      }
    }
    close(seg, info)
  }

  private fun feed(seg: Segment, pcm: ByteArray) {
    var offset = 0
    while (offset < pcm.size) {
      val idx = seg.codec.dequeueInputBuffer(10_000)
      if (idx < 0) return
      val buf = seg.codec.getInputBuffer(idx) ?: return
      buf.clear()
      val n = minOf(buf.remaining(), pcm.size - offset)
      buf.put(pcm, offset, n)
      val ptsUs = seg.samplesIn * 1_000_000L / RATE
      seg.codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
      seg.samplesIn += n / 2
      offset += n
    }
  }

  private fun drain(seg: Segment, info: MediaCodec.BufferInfo, endOfStream: Boolean) {
    val deadline = System.currentTimeMillis() + 2000 // never hang on a codec that skips end-of-stream
    while (!endOfStream || System.currentTimeMillis() < deadline) {
      val idx = seg.codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
      when {
        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          seg.track = seg.muxer.addTrack(seg.codec.outputFormat)
          seg.muxer.start()
          seg.muxing = true
        }
        idx >= 0 -> {
          val out = seg.codec.getOutputBuffer(idx)
          if (out != null && info.size > 0 && seg.muxing && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
            out.position(info.offset)
            out.limit(info.offset + info.size)
            seg.muxer.writeSampleData(seg.track, out, info)
          }
          seg.codec.releaseOutputBuffer(idx, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
      }
    }
  }

  private fun close(seg: Segment, info: MediaCodec.BufferInfo) {
    try {
      val idx = seg.codec.dequeueInputBuffer(50_000)
      if (idx >= 0) seg.codec.queueInputBuffer(idx, 0, 0, seg.samplesIn * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
      drain(seg, info, endOfStream = true)
    } catch (e: Exception) {
      Log.w(TAG, "audio drain failed: ${e.message}")
    }
    runCatching { seg.codec.stop() }
    runCatching { seg.codec.release() }
    val ok = seg.muxing && runCatching { seg.muxer.stop() }.isSuccess
    runCatching { seg.muxer.release() }
    if (ok && seg.file.length() > 0) onSegmentFinished?.invoke(seg.file) else seg.file.delete()
  }

  companion object {
    private const val TAG = "AudioRecorder"
    const val RATE = 16_000
  }
}
