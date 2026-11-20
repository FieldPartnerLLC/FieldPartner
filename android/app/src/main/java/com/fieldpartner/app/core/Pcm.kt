package com.fieldpartner.app.core

/** Small PCM helpers. Pure functions, unit tested. */
object Pcm {
  /**
   * Converts 16-bit little-endian PCM (any channel count, any rate) to mono at [outRate].
   * Linear interpolation is plenty for speech recognition.
   */
  fun toMono16(data: ByteArray, inRate: Int, channels: Int, outRate: Int = 16_000): ByteArray {
    if (channels <= 0 || inRate <= 0) return ByteArray(0)
    val frames = data.size / (2 * channels)
    if (frames == 0) return ByteArray(0)
    val mono = ShortArray(frames)
    for (f in 0 until frames) {
      var sum = 0
      for (c in 0 until channels) {
        val i = (f * channels + c) * 2
        sum += (data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)
      }
      mono[f] = (sum / channels).toShort()
    }
    if (inRate == outRate) return shortsToBytes(mono, frames)
    val outFrames = ((frames.toLong() * outRate) / inRate).toInt()
    val out = ShortArray(outFrames)
    val step = inRate.toDouble() / outRate
    for (i in 0 until outFrames) {
      val pos = i * step
      val i0 = pos.toInt().coerceAtMost(frames - 1)
      val i1 = (i0 + 1).coerceAtMost(frames - 1)
      val frac = pos - i0
      out[i] = (mono[i0] + (mono[i1] - mono[i0]) * frac).toInt().toShort()
    }
    return shortsToBytes(out, outFrames)
  }

  private fun shortsToBytes(s: ShortArray, n: Int): ByteArray {
    val b = ByteArray(n * 2)
    for (i in 0 until n) {
      val v = s[i].toInt()
      b[i * 2] = (v and 0xff).toByte()
      b[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
    }
    return b
  }

  /** Root-mean-square level, 0.0 to 1.0. Used for the mic level meter and silence checks. */
  fun rms(data: ByteArray): Double {
    val n = data.size / 2
    if (n == 0) return 0.0
    var acc = 0.0
    for (i in 0 until n) {
      val v = ((data[i * 2].toInt() and 0xff) or (data[i * 2 + 1].toInt() shl 8)).toShort().toDouble() / 32768.0
      acc += v * v
    }
    return kotlin.math.sqrt(acc / n)
  }
}
