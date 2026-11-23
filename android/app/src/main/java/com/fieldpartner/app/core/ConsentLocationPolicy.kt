package com.fieldpartner.app.core

/**
 * How the consent record gets stamped with where the customer signed. Pure logic, unit tested on
 * the JVM. The phone warms up a fix when the visit starts; at signing it uses that fix if recent,
 * else a fresh fix within [MAX_WAIT_MS], else a recent last known location. Consent is always saved,
 * with or without a location.
 */
object ConsentLocationPolicy {
  /** A fix this recent is good enough: the tech is at the customer's door. */
  const val MAX_FIX_AGE_MS = 10 * 60 * 1000L

  /** The consent screen waits at most this long for a fresh fix. */
  const val MAX_WAIT_MS = 5_000L

  fun isFresh(fixTimeMs: Long?, nowMs: Long): Boolean {
    if (fixTimeMs == null) return false
    val age = nowMs - fixTimeMs
    return age in 0..MAX_FIX_AGE_MS
  }

  /** Picks the newest fresh candidate (time in ms since epoch), or null if none is fresh. */
  fun <T> pick(candidates: List<Pair<T, Long>>, nowMs: Long): T? =
    candidates.filter { isFresh(it.second, nowMs) }.maxByOrNull { it.second }?.first
}
