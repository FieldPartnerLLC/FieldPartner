package com.fieldpartner.app.glasses

import android.content.Context
import java.nio.ByteBuffer
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoFrame

/**
 * Feeds glasses frames into a LiveKit video track. The "camera" is the DAT stream,
 * so start/stop only gate delivery; [GlassesManager] owns the DAT session.
 */
class FrameCapturer : VideoCapturer {
  @Volatile private var observer: CapturerObserver? = null
  @Volatile private var capturing = false
  @Volatile var framesSent: Long = 0
    private set

  override fun initialize(surfaceTextureHelper: SurfaceTextureHelper?, context: Context?, capturerObserver: CapturerObserver?) {
    observer = capturerObserver
  }

  override fun startCapture(width: Int, height: Int, framerate: Int) {
    capturing = true
    observer?.onCapturerStarted(true)
  }

  override fun stopCapture() {
    capturing = false
    observer?.onCapturerStopped()
  }

  override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {}

  override fun dispose() {
    capturing = false
    observer = null
  }

  override fun isScreencast(): Boolean = false

  /**
   * DAT delivers packed I420 (Y, then U, then V). The buffer is only valid during the
   * callback, so it is copied into a WebRTC-owned buffer.
   */
  fun pushI420(src: ByteBuffer, width: Int, height: Int) {
    val obs = observer ?: return
    if (!capturing || width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return
    val ySize = width * height
    val cSize = ySize / 4
    val base = src.position()
    if (src.remaining() < ySize + 2 * cSize) return
    val i420 = JavaI420Buffer.allocate(width, height)
    val dup = src.duplicate()
    dup.limit(base + ySize).position(base)
    i420.dataY.put(dup)
    dup.limit(base + ySize + cSize).position(base + ySize)
    i420.dataU.put(dup)
    dup.limit(base + ySize + 2 * cSize).position(base + ySize + cSize)
    i420.dataV.put(dup)
    val frame = VideoFrame(i420, 0, System.nanoTime())
    obs.onFrameCaptured(frame)
    frame.release()
    framesSent++
  }
}
