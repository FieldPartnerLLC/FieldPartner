package com.fieldpartner.app.core

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/**
 * Visit events are written to disk first and delivered later, so nothing is lost
 * when the phone goes into a basement, the app is killed, or the server is down.
 * Delivery is at-least-once; the server de-duplicates by event id.
 */
class EventQueue(visitId: String, directory: File) {
  private val file = File(directory, "events-$visitId.json")
  private val lock = Mutex()
  private val flushLock = Mutex()
  private val pending = mutableListOf<VisitEvent>()
  private val ser = ListSerializer(VisitEvent.serializer())

  @Volatile var lastError: String? = null
    private set

  init {
    directory.mkdirs()
    if (file.exists()) {
      runCatching { pending.addAll(FpJson.decodeFromString(ser, file.readText())) }
        .onFailure { file.renameTo(File(directory, file.name + ".corrupt-${System.currentTimeMillis()}")) }
    }
  }

  suspend fun count(): Int = lock.withLock { pending.size }

  suspend fun add(event: VisitEvent) =
    lock.withLock {
      pending.add(event)
      persist()
    }

  /** Sends pending events in batches of 200. Returns how many were delivered. */
  suspend fun flush(send: suspend (List<VisitEvent>) -> Unit): Int {
    if (!flushLock.tryLock()) return 0
    try {
      var delivered = 0
      while (true) {
        val batch = lock.withLock { pending.take(200) }
        if (batch.isEmpty()) break
        try {
          send(batch)
          remove(batch)
          delivered += batch.size
          lastError = null
        } catch (e: CancellationException) {
          throw e
        } catch (e: ApiException) {
          lastError = e.message
          if (!e.isTransient && e !is ApiException.NotSignedIn) {
            // The server rejected this batch outright; retrying would loop forever.
            remove(batch)
          } else {
            break
          }
        } catch (e: Exception) {
          lastError = e.message
          break
        }
      }
      return delivered
    } finally {
      flushLock.unlock()
    }
  }

  private suspend fun remove(batch: List<VisitEvent>) =
    lock.withLock {
      val ids = batch.mapTo(HashSet()) { it.id }
      pending.removeAll { it.id in ids }
      persist()
    }

  fun discardFile() {
    file.delete()
  }

  private fun persist() {
    try {
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.writeText(FpJson.encodeToString(ser, pending))
      if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
      }
    } catch (_: Exception) {
      // Disk full or similar. Events stay in memory and are retried on the next add.
    }
  }
}
