package com.fieldpartner.app.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Records the glasses (or phone camera) video to H.264 .mp4 segments.
 * Frames arrive as tightly packed I420 (Y plane, then U, then V). Encoding runs on its
 * own thread; if the encoder falls behind, frames are dropped rather than blocking the
 * live call. Segments roll every [segmentMs] and on pause, so a pause leaves a clean file.
 */
class VideoSegmentRecorder(private val dir: File, private val prefix: String, private val segmentMs: Long = 5 * 60_000L) {
  var onSegmentFinished: ((File) -> Unit)? = null

  private class Frame(val data: ByteArray, val width: Int, val height: Int, val ptsUs: Long)

  private val queue = ArrayBlockingQueue<Frame>(6)
  @Volatile private var running = false
  @Volatile private var acceptFrames = false
  private var thread: Thread? = null

  val isRecording: Boolean
    get() = running && acceptFrames

  fun start() {
    acceptFrames = true
    if (running) return
    dir.mkdirs()
    running = true
    thread = Thread({ loop() }, "fp-video-rec").also { it.start() }
  }

  /** Stops writing and closes the current segment; [start] opens a new one. */
  fun pause() {
    acceptFrames = false
    queue.offer(PAUSE)
  }

  fun stop() {
    if (!running) return
    acceptFrames = false
    running = false
    thread?.join(5000)
    thread = null
    queue.clear()
  }

  /** Copies one packed I420 frame. Safe to call from any thread. */
  fun appendI420(src: ByteBuffer, width: Int, height: Int) {
    if (!acceptFrames || width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return
    val size = width * height * 3 / 2
    if (src.remaining() < size) return
    if (queue.remainingCapacity() == 0) return // encoder busy: drop this frame
    val bytes = ByteArray(size)
    src.duplicate().get(bytes)
    queue.offer(Frame(bytes, width, height, System.nanoTime() / 1000))
  }

  /** Same as [appendI420] for frames split into planes with strides (WebRTC I420 buffers). */
  fun appendPlanes(y: ByteBuffer, strideY: Int, u: ByteBuffer, strideU: Int, v: ByteBuffer, strideV: Int, width: Int, height: Int) {
    if (!acceptFrames || width % 2 != 0 || height % 2 != 0 || queue.remainingCapacity() == 0) return
    val out = ByteArray(width * height * 3 / 2)
    var o = 0
    val yb = y.duplicate()
    for (r in 0 until height) {
      yb.position(r * strideY)
      yb.get(out, o, width)
      o += width
    }
    val cw = width / 2
    val ch = height / 2
    for ((plane, stride) in listOf(u.duplicate() to strideU, v.duplicate() to strideV)) {
      for (r in 0 until ch) {
        plane.position(r * stride)
        plane.get(out, o, cw)
        o += cw
      }
    }
    queue.offer(Frame(out, width, height, System.nanoTime() / 1000))
  }

  private class Segment(val file: File, val codec: MediaCodec, val muxer: MediaMuxer, val width: Int, val height: Int, val startedAt: Long, val firstPtsUs: Long) {
    var track = -1
    var muxing = false
    var lastPtsUs = 0L
  }

  private fun open(width: Int, height: Int, firstPts: Long): Segment? =
    try {
      val file = File(dir, "${prefix}-video-${System.currentTimeMillis()}.mp4")
      val fmt =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
          setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
          setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
          setInteger(MediaFormat.KEY_FRAME_RATE, 15)
          setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
      val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
      codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      codec.start()
      Segment(file, codec, MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), width, height, System.currentTimeMillis(), firstPts)
    } catch (e: Exception) {
      Log.e(TAG, "cannot open video segment ${width}x$height: ${e.message}")
      null
    }

  private fun loop() {
    var seg: Segment? = null
    val info = MediaCodec.BufferInfo()
    while (running) {
      val f = queue.poll(200, TimeUnit.MILLISECONDS)
      if (f === PAUSE) {
        seg?.let { close(it, info) }
        seg = null
        continue
      }
      if (f != null) {
        val current = seg
        if (current == null || current.width != f.width || current.height != f.height || System.currentTimeMillis() - current.startedAt >= segmentMs) {
          current?.let { close(it, info) }
          seg = open(f.width, f.height, f.ptsUs)
        }
        seg?.let { feed(it, f) }
      }
      seg?.let { drain(it, info, endOfStream = false) }
    }
    seg?.let { close(it, info) }
  }

  private fun feed(seg: Segment, f: Frame) {
    val idx = seg.codec.dequeueInputBuffer(20_000)
    if (idx < 0) return
    val image = seg.codec.getInputImage(idx)
    val pts = maxOf(seg.lastPtsUs + 1, f.ptsUs - seg.firstPtsUs)
    seg.lastPtsUs = pts
    if (image == null) {
      // Rare encoders without Image input: assume planar I420 layout.
      val buf = seg.codec.getInputBuffer(idx) ?: return
      buf.clear()
      buf.put(f.data, 0, minOf(f.data.size, buf.remaining()))
      seg.codec.queueInputBuffer(idx, 0, f.data.size, pts, 0)
      return
    }
    val w = f.width
    val h = f.height
    val planes = image.planes
    copyPlane(f.data, 0, w, h, planes[0].buffer, planes[0].rowStride, planes[0].pixelStride)
    copyPlane(f.data, w * h, w / 2, h / 2, planes[1].buffer, planes[1].rowStride, planes[1].pixelStride)
    copyPlane(f.data, w * h + (w / 2) * (h / 2), w / 2, h / 2, planes[2].buffer, planes[2].rowStride, planes[2].pixelStride)
    seg.codec.queueInputBuffer(idx, 0, w * h * 3 / 2, pts, 0)
  }

  private fun copyPlane(src: ByteArray, offset: Int, w: Int, h: Int, dst: ByteBuffer, rowStride: Int, pixelStride: Int) {
    if (pixelStride == 1) {
      for (r in 0 until h) {
        dst.position(r * rowStride)
        dst.put(src, offset + r * w, w)
      }
    } else {
      for (r in 0 until h) {
        val rowBase = r * rowStride
        for (c in 0 until w) {
          val pos = rowBase + c * pixelStride
          if (pos < dst.limit()) dst.put(pos, src[offset + r * w + c])
        }
      }
    }
  }

  private fun drain(seg: Segment, info: MediaCodec.BufferInfo, endOfStream: Boolean) {
    val deadline = System.currentTimeMillis() + 2000 // never hang on a codec that skips end-of-stream
    while (!endOfStream || System.currentTimeMillis() < deadline) {
      val idx = seg.codec.dequeueOutputBuffer(info, if (endOfStream) 20_000 else 0)
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
      seg.codec.signalEndOfInputStream()
    } catch (_: Exception) {
      // Not a Surface encoder: send an empty EOS buffer instead.
      runCatching {
        val idx = seg.codec.dequeueInputBuffer(50_000)
        if (idx >= 0) seg.codec.queueInputBuffer(idx, 0, 0, seg.lastPtsUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
      }
    }
    runCatching { drain(seg, info, endOfStream = true) }
    runCatching { seg.codec.stop() }
    runCatching { seg.codec.release() }
    val ok = seg.muxing && runCatching { seg.muxer.stop() }.isSuccess
    runCatching { seg.muxer.release() }
    if (ok && seg.file.length() > 0) onSegmentFinished?.invoke(seg.file) else seg.file.delete()
  }

  companion object {
    private const val TAG = "VideoRecorder"
    private val PAUSE = Frame(ByteArray(0), 0, 0, 0)
  }
}
