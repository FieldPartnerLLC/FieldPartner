package com.fieldpartner.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTest {
  private fun pcm(vararg s: Int): ByteArray = ByteArray(s.size * 2).also { b -> s.forEachIndexed { i, v -> b[i * 2] = (v and 0xff).toByte(); b[i * 2 + 1] = (v shr 8).toByte() } }

  @Test fun sameRateMonoIsUnchanged() {
    val src = pcm(1, -2, 300, -32768, 32767)
    assertTrue(src.contentEquals(Pcm.toMono16(src, 16_000, 1, 16_000)))
  }

  @Test fun stereoIsAveraged() {
    val out = Pcm.toMono16(pcm(100, 300, -100, -300), 16_000, 2, 16_000)
    assertEquals(4, out.size)
    assertEquals(200, (out[0].toInt() and 0xff) or (out[1].toInt() shl 8))
  }

  @Test fun downsample48kTo16kKeepsDuration() {
    val oneSecond = ByteArray(48_000 * 2)
    assertEquals(16_000 * 2, Pcm.toMono16(oneSecond, 48_000, 1, 16_000).size)
  }

  @Test fun oddInputsDoNotCrash() {
    assertEquals(0, Pcm.toMono16(ByteArray(1), 48_000, 1).size)
    assertEquals(0, Pcm.toMono16(ByteArray(10), 0, 1).size)
    assertEquals(0.0, Pcm.rms(ByteArray(0)), 0.0)
  }

  @Test fun rmsOfFullScaleSquareIsOne() = assertEquals(1.0, Pcm.rms(pcm(-32768, -32768)), 0.001)
}
