package com.fieldpartner.app.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApiClientTest {
  private val server = MockWebServer().also { it.start() }
  private val api = ApiClient(server.url("/").toString(), "tok", retryDelayMs = 1)

  @After fun stop() = server.shutdown()

  private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

  @Test fun sendsBearerAndParsesJobs() = runBlocking {
    server.enqueue(json(200, """{"jobs":[{"id":"j1","company_id":"c","trade":"hvac","title":"No heat","status":"scheduled","extra_field":1,"last_visit":null}]}"""))
    val jobs = api.jobs()
    assertEquals("No heat", jobs.single().title)
    val req = server.takeRequest()
    assertEquals("Bearer tok", req.getHeader("Authorization"))
    assertTrue(req.path!!.startsWith("/jobs?date="))
  }

  @Test fun retriesServerHiccupsThenSucceeds() = runBlocking {
    server.enqueue(json(503, """{"error":"busy"}"""))
    server.enqueue(json(502, ""))
    server.enqueue(json(200, """{"url":"wss://x","room":"visit-1","token":"t","identity":"tech-1"}"""))
    assertEquals("visit-1", api.liveTicket("v1").room)
    assertEquals(3, server.requestCount)
  }

  @Test fun doesNotRetryRealErrorsAndShowsServerMessage() = runBlocking {
    server.enqueue(json(409, """{"error":"visit_open","message":"End the visit before approving the report."}"""))
    try {
      api.approveReport("v1", ServiceReport(), true, true)
      fail()
    } catch (e: ApiException.Server) {
      assertEquals(409, e.status)
      assertEquals("End the visit before approving the report.", e.message)
      assertFalse(e.isTransient)
    }
    assertEquals(1, server.requestCount)
  }

  @Test fun unauthorizedMeansSignInAgain() = runBlocking {
    server.enqueue(json(401, """{"error":"unauthorized"}"""))
    try {
      api.me()
      fail()
    } catch (e: ApiException.NotSignedIn) {}
  }

  @Test fun offlineIsTransient() = runBlocking {
    val dead = ApiClient("http://127.0.0.1:9", "t", retryDelayMs = 1)
    try {
      dead.me()
      fail()
    } catch (e: ApiException) {
      assertTrue(e is ApiException.Offline)
      assertTrue(e.isTransient)
    }
  }

  @Test fun consentOmitsNullsBecauseServerRejectsThem() = runBlocking {
    server.enqueue(json(200, """{"consentId":"c1","recording":"none","cloudRecording":false}"""))
    api.consent("v1", ConsentBody(kind = "recording_revoked"))
    val body = server.takeRequest().body.readUtf8()
    assertFalse(body, body.contains("null"))
    assertTrue(body.contains("\"kind\":\"recording_revoked\""))
  }

  @Test fun eventsSerializeAsServerExpects() = runBlocking {
    server.enqueue(json(200, """{"stored":1,"duplicates":0}"""))
    api.postEvents("v1", listOf(VisitEvent.create("reading", mapOf("name" to "CO", "value" to 12, "unit" to "ppm"))))
    val body = FpJson.parseToJsonElement(server.takeRequest().body.readUtf8()).toString()
    assertTrue(body.contains("\"type\":\"reading\""))
    assertTrue(body.contains("\"value\":12"))
    assertTrue(body.contains("\"actor\":\"tech\""))
  }

  @Test fun badBaseUrlGivesReadableError() = runBlocking {
    try {
      ApiClient("not a url", "t").me()
      fail()
    } catch (e: ApiException.Server) {
      assertTrue(e.message!!.contains("Settings"))
    }
  }

  @Test fun noTokenMeansSignIn() = runBlocking {
    try {
      ApiClient(server.url("/").toString(), null).me()
      fail()
    } catch (e: ApiException.NotSignedIn) {}
  }
}
