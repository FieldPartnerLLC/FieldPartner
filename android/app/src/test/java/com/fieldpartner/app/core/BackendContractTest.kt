package com.fieldpartner.app.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the Android API layer against the real FieldPartner backend: the same calls the
 * app makes during a visit, in the same order. Skipped unless FP_BACKEND_URL is set
 * (scripts/test-android.sh starts a throwaway backend and sets it).
 */
class BackendContractTest {
  private val base = System.getenv("FP_BACKEND_URL")
  private val email = System.getenv("FP_TECH_EMAIL") ?: "tech@fieldpartner.local"
  private val password = System.getenv("FP_TECH_PASSWORD") ?: ""

  @Test fun fullVisitAgainstRealBackend() = runBlocking {
    assumeTrue("FP_BACKEND_URL not set", !base.isNullOrBlank() && password.isNotBlank())
    val api = ApiClient(base!!, null)
    val login = api.login(email, password)
    api.token = login.token
    assertEquals("tech", login.user.role)

    val me = api.me()
    assertTrue(me.trades.size >= 8)
    assertNotNull(me.company?.wake_phrase)

    val job = api.createJob("plumbing", "Android contract test", "Water heater leaking", "1 Test St", "Pat Customer", "+15555550100")
    val start = api.startVisit(job.id)
    val again = api.startVisit(job.id)
    assertEquals("starting twice resumes the same visit", start.visit.id, again.visit.id)
    val visitId = start.visit.id

    // The live ticket is 503 without LiveKit keys; with keys it must be a usable ticket.
    runCatching { api.liveTicket(visitId) }.onSuccess { assertTrue(it.url.startsWith("ws")) }
      .onFailure { assertTrue(it.message, it is ApiException.Server && it.status == 503) }

    val c = api.consent(visitId, ConsentBody(kind = "service_and_recording", choice = "video_audio", privateAreasOk = false, signerName = "Pat Customer", signerRole = "owner", language = "en"))
    assertNotNull(c.consentId)

    val dir: File = Files.createTempDirectory("contract").toFile()
    val q = EventQueue(visitId, dir)
    q.add(VisitEvent.create("state", mapOf("state" to "active"), actor = "system"))
    q.add(VisitEvent.create("reading", mapOf("name" to "CO ambient", "value" to "4", "unit" to "ppm")))
    q.add(VisitEvent.create("transcript", mapOf("speaker" to "room", "text" to "hey dispatch is this anode rod gone")))
    q.add(VisitEvent.create("equipment", mapOf("type" to "water heater", "brand" to "Rheem", "model" to "XE50M06ST45U1")))
    q.add(VisitEvent.create("note", mapOf("text" to "T&P valve dripping, tank 11 years old")))
    q.add(VisitEvent.create("device", mapOf("platform" to "android"), actor = "system"))
    val events = q.flush { batch -> api.postEvents(visitId, batch) }
    assertEquals(6, events)
    // Resending the same events is harmless (at-least-once delivery).
    val dup = api.postEvents(visitId, listOf(VisitEvent("and-fixed-id", java.time.Instant.now().toString(), "note"), VisitEvent("and-fixed-id", java.time.Instant.now().toString(), "note")))
    assertEquals(1, dup.stored + 0)

    val esc = api.escalate(visitId, "Android contract test")
    assertTrue(esc.status.isNotBlank())
    api.endEscalation(visitId)

    val f = File(dir, "transcript.txt").also { it.writeText("hey dispatch is this anode rod gone") }
    val up = UploadQueue(File(dir, "state"), backoffBaseMs = 10)
    up.enqueue(visitId, f, "transcript", "text/plain")
    assertEquals(1, up.process(api.asUploadApi(), onWifi = true, allowCellularVideo = false))

    val ended = api.endVisit(visitId)
    assertNotNull(ended.ended_at)

    var report: ReportResponse? = null
    for (i in 0 until 40) {
      report = api.report(visitId)
      if (report.report != null || report.status == "failed") break
      delay(500)
    }
    assertNotNull("report draft", report?.report)
    val approved = api.approveReport(visitId, report!!.report!!.cleaned(), sendSms = false, sendEmail = false)
    assertTrue(approved.url.contains(approved.token))
  }
}
