package com.fieldpartner.app.core

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What the uploader needs from the server. ApiClient implements it; tests use a fake. */
interface UploadApi {
  suspend fun requestUpload(visitId: String, kind: String, filename: String, contentType: String, bytes: Long, clientId: String): UploadTicket
  suspend fun upload(file: File, url: String, contentType: String)
  suspend fun completeUpload(mediaId: String, bytes: Long)
  val baseUrl: String
}

fun ApiClient.asUploadApi(): UploadApi {
  val api = this
  return object : UploadApi {
    override suspend fun requestUpload(visitId: String, kind: String, filename: String, contentType: String, bytes: Long, clientId: String) =
      api.requestUpload(visitId, kind, filename, contentType, bytes, clientId)

    override suspend fun upload(file: File, url: String, contentType: String) = api.upload(file, url, contentType)

    override suspend fun completeUpload(mediaId: String, bytes: Long) = api.completeUpload(mediaId, bytes)

    override val baseUrl: String
      get() = api.baseUrl
  }
}

/**
 * Uploads recordings after (or during) visits. Survives app restarts, waits for
 * Wi-Fi for video unless the user allows cellular, and retries with backoff.
 * A file is deleted from the phone only after the server confirms it.
 */
class UploadQueue(stateDir: File, private val backoffBaseMs: Long = 1000) {
  @Serializable
  data class Item(val visitId: String, val path: String, val kind: String, val contentType: String, val clientId: String, val attempts: Int = 0)

  private val stateFile = File(stateDir, "uploads.json")
  private val ser = ListSerializer(Item.serializer())
  private val lock = Mutex()
  private val runLock = Mutex()
  private val items = mutableListOf<Item>()

  init {
    stateDir.mkdirs()
    if (stateFile.exists()) runCatching { items.addAll(FpJson.decodeFromString(ser, stateFile.readText())) }
  }

  suspend fun pendingCount(): Int = lock.withLock { items.size }

  suspend fun snapshot(): List<Item> = lock.withLock { items.toList() }

  suspend fun enqueue(visitId: String, file: File, kind: String, contentType: String) {
    if (!file.exists()) return
    val item = Item(visitId, file.absolutePath, kind, contentType, clientId = "$visitId-${file.name}".take(100))
    lock.withLock {
      if (items.none { it.clientId == item.clientId }) {
        items.add(item)
        save()
      }
    }
  }

  /**
   * One pass over the queue. [onWifi] false skips video unless [allowCellularVideo].
   * Returns the number of files uploaded.
   */
  suspend fun process(api: UploadApi, onWifi: Boolean, allowCellularVideo: Boolean): Int {
    if (!runLock.tryLock()) return 0
    try {
      var uploaded = 0
      for (item in snapshot()) {
        if (item.kind == "video" && !onWifi && !allowCellularVideo) continue
        val file = File(item.path)
        if (!file.exists()) {
          drop(item)
          continue
        }
        try {
          val size = file.length()
          val ticket = api.requestUpload(item.visitId, item.kind, file.name, item.contentType, size, item.clientId)
          if (ticket.alreadyUploaded != true) {
            val target = ticket.url ?: throw ApiException.BadResponse(" (no upload url)")
            api.upload(file, rewriteLocalhost(target, api.baseUrl), item.contentType)
            api.completeUpload(ticket.mediaId, size)
          }
          drop(item)
          file.delete()
          uploaded++
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          val attempts = bump(item)
          // Back off a little per failure so a dead network doesn't spin the radio.
          delay(minOf(60_000L, backoffBaseMs * (1L shl minOf(attempts, 6))))
        }
      }
      return uploaded
    } finally {
      runLock.unlock()
    }
  }

  private suspend fun drop(item: Item) =
    lock.withLock {
      items.removeAll { it.clientId == item.clientId }
      save()
    }

  private suspend fun bump(item: Item): Int =
    lock.withLock {
      val i = items.indexOfFirst { it.clientId == item.clientId }
      if (i < 0) return@withLock 0
      items[i] = items[i].copy(attempts = items[i].attempts + 1)
      save()
      items[i].attempts
    }

  private fun save() {
    runCatching {
      val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
      tmp.writeText(FpJson.encodeToString(ser, items))
      if (!tmp.renameTo(stateFile)) {
        stateFile.delete()
        tmp.renameTo(stateFile)
      }
    }
  }

  companion object {
    /**
     * Local-storage mode signs URLs with the server's PUBLIC_API_URL. If that says
     * localhost (a misconfigured Mac), point it at the address the phone already uses.
     */
    fun rewriteLocalhost(url: String, apiBase: String): String {
      val u = url.toHttpUrlOrNull() ?: return url
      if (u.host != "localhost" && u.host != "127.0.0.1") return url
      val b = apiBase.toHttpUrlOrNull() ?: return url
      return u.newBuilder().scheme(b.scheme).host(b.host).port(b.port).build().toString()
    }
  }
}
