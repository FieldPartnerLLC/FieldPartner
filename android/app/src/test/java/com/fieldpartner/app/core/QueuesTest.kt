package com.fieldpartner.app.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventQueueTest {
  private val dir: File = Files.createTempDirectory("eq").toFile()

  @Test fun survivesRestartAndDeliversOnce() = runBlocking {
    val q = EventQueue("v1", dir)
    repeat(450) { q.add(VisitEvent.create("transcript", mapOf("text" to "line $it"))) }
    // App killed before delivery: a new queue reads the same file.
    val q2 = EventQueue("v1", dir)
    assertEquals(450, q2.count())
    val batches = mutableListOf<Int>()
    val sent = q2.flush { batches += it.size }
    assertEquals(450, sent)
    assertEquals(listOf(200, 200, 50), batches)
    assertEquals(0, EventQueue("v1", dir).count())
  }

  @Test fun offlineKeepsEventsAndRetriesLater() = runBlocking {
    val q = EventQueue("v2", dir)
    q.add(VisitEvent.create("note", mapOf("text" to "a")))
    assertEquals(0, q.flush { throw ApiException.Offline() })
    assertEquals(1, q.count())
    assertEquals(1, q.flush { })
    assertEquals(0, q.count())
  }

  @Test fun rejectedBatchIsDroppedSoItCannotLoopForever() = runBlocking {
    val q = EventQueue("v3", dir)
    q.add(VisitEvent.create("note"))
    q.flush { throw ApiException.Server(400, "bad_event", "Unknown event type") }
    assertEquals(0, q.count())
  }

  @Test fun signedOutKeepsEvents() = runBlocking {
    val q = EventQueue("v4", dir)
    q.add(VisitEvent.create("note"))
    q.flush { throw ApiException.NotSignedIn() }
    assertEquals(1, q.count())
  }

  @Test fun corruptFileIsSetAsideNotFatal() = runBlocking {
    File(dir, "events-v5.json").writeText("{{{ not json")
    val q = EventQueue("v5", dir)
    assertEquals(0, q.count())
    assertTrue(dir.listFiles()!!.any { it.name.startsWith("events-v5.json.corrupt") })
  }

  @Test fun eventIdsAreUniqueAndPrefixed() {
    val ids = (1..1000).map { VisitEvent.create("note").id }.toSet()
    assertEquals(1000, ids.size)
    assertTrue(ids.all { it.startsWith("and-") && it.length <= 100 })
  }
}

class UploadQueueTest {
  private val dir: File = Files.createTempDirectory("uq").toFile()

  private class FakeApi(var failTimes: Int = 0, var already: Boolean = false) : UploadApi {
    val uploaded = mutableListOf<String>()
    val urls = mutableListOf<String>()
    var completes = 0
    override val baseUrl = "http://192.168.1.50:8080"

    override suspend fun requestUpload(visitId: String, kind: String, filename: String, contentType: String, bytes: Long, clientId: String): UploadTicket {
      if (failTimes > 0) {
        failTimes--
        throw ApiException.Offline()
      }
      return UploadTicket("m-$filename", url = if (already) null else "http://localhost:8080/media/local/$filename?sig=1", alreadyUploaded = already)
    }

    override suspend fun upload(file: File, url: String, contentType: String) {
      uploaded += file.name
      urls += url
    }

    override suspend fun completeUpload(mediaId: String, bytes: Long) {
      completes++
    }
  }

  private fun file(name: String) = File(dir, name).also { it.writeText("x".repeat(100)) }

  @Test fun uploadsDeletesAndPersists() = runBlocking {
    val q = UploadQueue(File(dir, "state"), backoffBaseMs = 1)
    val a = file("a.m4a")
    q.enqueue("v1", a, "audio", "audio/mp4")
    q.enqueue("v1", a, "audio", "audio/mp4") // duplicate ignored
    assertEquals(1, UploadQueue(File(dir, "state")).pendingCount())
    val api = FakeApi()
    assertEquals(1, q.process(api, onWifi = true, allowCellularVideo = false))
    assertFalse(a.exists())
    assertEquals(0, q.pendingCount())
    // Local-storage URL that says localhost is pointed at the phone's server address.
    assertEquals("http://192.168.1.50:8080/media/local/a.m4a?sig=1", api.urls.single())
  }

  @Test fun videoWaitsForWifi() = runBlocking {
    val q = UploadQueue(File(dir, "s2"), backoffBaseMs = 1)
    q.enqueue("v1", file("v.mp4"), "video", "video/mp4")
    q.enqueue("v1", file("t.txt"), "transcript", "text/plain")
    val api = FakeApi()
    assertEquals(1, q.process(api, onWifi = false, allowCellularVideo = false))
    assertEquals(listOf("t.txt"), api.uploaded)
    assertEquals(1, q.process(api, onWifi = true, allowCellularVideo = false))
  }

  @Test fun failureKeepsFileAndCountsAttempt() = runBlocking {
    val q = UploadQueue(File(dir, "s3"), backoffBaseMs = 1)
    val f = file("b.m4a")
    q.enqueue("v1", f, "audio", "audio/mp4")
    val api = FakeApi(failTimes = 1)
    assertEquals(0, q.process(api, true, false))
    assertTrue(f.exists())
    assertEquals(1, q.snapshot().single().attempts)
    assertEquals(1, q.process(api, true, false))
  }

  @Test fun alreadyUploadedOnServerIsNotSentTwice() = runBlocking {
    val q = UploadQueue(File(dir, "s4"), backoffBaseMs = 1)
    q.enqueue("v1", file("c.m4a"), "audio", "audio/mp4")
    val api = FakeApi(already = true)
    assertEquals(1, q.process(api, true, false))
    assertTrue(api.uploaded.isEmpty())
    assertEquals(0, api.completes)
  }

  @Test fun missingFileIsDropped() = runBlocking {
    val q = UploadQueue(File(dir, "s5"), backoffBaseMs = 1)
    val f = file("d.m4a")
    q.enqueue("v1", f, "audio", "audio/mp4")
    f.delete()
    assertEquals(0, q.process(FakeApi(), true, false))
    assertEquals(0, q.pendingCount())
  }

  @Test fun rewriteOnlyTouchesLocalhost() {
    assertEquals("https://bucket.s3.amazonaws.com/x?sig", UploadQueue.rewriteLocalhost("https://bucket.s3.amazonaws.com/x?sig", "http://10.0.2.2:8080"))
    assertEquals("http://10.0.2.2:8080/media/local/k?e=1", UploadQueue.rewriteLocalhost("http://127.0.0.1:8080/media/local/k?e=1", "http://10.0.2.2:8080"))
  }
}
