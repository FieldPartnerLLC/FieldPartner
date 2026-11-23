package com.fieldpartner.app.core

/**
 * Encoding for the glasses video sent to the live room. The AI samples 1 frame a second, so with
 * only the AI watching the phone sends 5 fps at 1 Mbps (about 0.45 GB per visit hour on cellular).
 * A human expert gets smooth video for as long as they are in the call.
 */
data class LiveVideoProfile(val maxBitrateBps: Int, val maxFps: Int) {
  companion object {
    val AI_ONLY = LiveVideoProfile(1_000_000, 5)
    val EXPERT = LiveVideoProfile(2_500_000, 15)

    fun forRoom(expertPresent: Boolean): LiveVideoProfile = if (expertPresent) EXPERT else AI_ONLY
  }
}

/**
 * Decides when the published glasses track has to be republished with another [LiveVideoProfile].
 * LiveKit has no public call to change a published track's encoding, so a switch is an unpublish
 * plus a publish. Never switches while video is muted (privacy pause or no camera consent), and
 * never starts a second switch while one is running. Call from one thread.
 */
class LiveVideoBoost {
  /** Profile the track is published with now, or null while it is not published. */
  var published: LiveVideoProfile? = null
    private set
  var expertPresent = false
    private set
  var muted = false
    private set
  private var switching = false

  /** Profile to use for a fresh publish. */
  val wanted: LiveVideoProfile
    get() = LiveVideoProfile.forRoom(expertPresent)

  fun onPublished(profile: LiveVideoProfile) {
    published = profile
  }

  fun onUnpublished() {
    published = null
  }

  /** Returns the profile to switch to now, or null when nothing has to change. */
  fun setExpertPresent(present: Boolean): LiveVideoProfile? {
    expertPresent = present
    return next()
  }

  /** Returns the profile to switch to now, or null when nothing has to change. */
  fun setMuted(isMuted: Boolean): LiveVideoProfile? {
    muted = isMuted
    return next()
  }

  /**
   * A switch ended. [profile] is what the track is published with now, or null if the publish
   * failed. Returns the next profile to switch to if the room changed during the switch.
   */
  fun onSwitchDone(profile: LiveVideoProfile?): LiveVideoProfile? {
    switching = false
    published = profile
    return next()
  }

  fun reset() {
    published = null
    expertPresent = false
    muted = false
    switching = false
  }

  private fun next(): LiveVideoProfile? {
    val current = published ?: return null
    if (switching || muted) return null
    val target = wanted
    if (target == current) return null
    switching = true
    return target
  }
}
