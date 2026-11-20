package com.fieldpartner.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldpartner.app.FieldPartnerApp
import com.fieldpartner.app.MainActivity
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real Compose screens on the JVM (Robolectric) against a fake backend:
 * sign in, see today's jobs, create a walk-in, open it, change settings, sign out.
 * The visit screen itself needs WebRTC and the glasses toolkit, so it is tested on the
 * emulator (docs/ANDROID-TESTING.md) and the visit API flow by BackendContractTest.
 */
@RunWith(AndroidJUnit4::class)
class ScreenFlowTest {
  private val server = MockWebServer()
  private val requests = mutableListOf<String>()

  init {
    server.start()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val path = request.path ?: ""
          synchronized(requests) { requests += "${request.method} ${path.substringBefore('?')}" }
          fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
          return when {
            path == "/auth/login" ->
              if (request.body.readUtf8().contains("wrong")) MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized","message":"Wrong email or password."}""")
              else ok("""{"token":"T1","user":$USER,"company":$COMPANY}""")
            path == "/me" -> ok("""{"user":$USER,"company":$COMPANY,"trades":[{"id":"hvac","name":"HVAC","profile":{"preWorkReadings":[{"key":"co","label":"CO ambient","unit":"ppm"}],"stopRules":["CO above 35 ppm"]}},{"id":"plumbing","name":"Plumbing","profile":{}}]}""")
            path.startsWith("/jobs") && request.method == "GET" -> ok("""{"jobs":[$JOB]}""")
            path == "/jobs" && request.method == "POST" -> ok("""{"job":{"id":"j2","company_id":"c1","trade":"plumbing","title":"Leaking water heater","status":"scheduled","customer_name":"Pat"}}""")
            else -> MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}""")
          }
        }
      }
  }

  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Before fun pointAppAtFakeServer() {
    val app = ApplicationProvider.getApplicationContext<FieldPartnerApp>()
    compose.runOnUiThread { compose.activity.vm.logout() } // start every test signed out
    compose.waitForIdle()
    compose.onNodeWithTag("server").performTextReplacement(server.url("/").toString())
  }

  @After fun stop() = server.shutdown()

  private fun waitForText(t: String) {
    try {
      compose.waitUntil(10_000) { compose.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
      org.robolectric.shadows.ShadowLog.stream = System.out
      compose.onRoot().printToLog("FPTREE")
      val app = ApplicationProvider.getApplicationContext<FieldPartnerApp>()
      println("FPDEBUG base=${app.api.baseUrl} token=${app.api.token}")
      throw AssertionError("'$t' not shown. Requests: ${synchronized(requests) { requests.toList() }}", e)
    }
  }

  @Test fun wrongPasswordShowsServerMessage() {
    compose.onNodeWithTag("email").performTextInput("tech@x.com")
    compose.onNodeWithTag("password").performTextInput("wrong")
    compose.onNodeWithTag("signin").performScrollTo().performClick()
    waitForText("Wrong email or password")
  }

  @Test fun signInSeeJobsCreateWalkInOpenItAndSignOut() {
    compose.onNodeWithText("FieldPartner").assertIsDisplayed()
    compose.onNodeWithTag("email").performTextInput("tech@fieldpartner.local")
    compose.onNodeWithTag("password").performTextInput("secret")
    compose.onNodeWithTag("signin").performScrollTo().performClick()

    // Jobs list from the server.
    waitForText("No heat upstairs")
    compose.onNodeWithText("No heat upstairs").performClick()
    waitForText("Start visit")
    compose.onNodeWithText("CO ambient", substring = true).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("CO above 35 ppm", substring = true).performScrollTo().assertIsDisplayed()
    compose.onNodeWithContentDescription("Back").performClick()

    // Walk-in job.
    waitForText("Today")
    compose.onNodeWithContentDescription("Walk-in job").performClick()
    waitForText("Create job")
    compose.onNodeWithText("Job title, e.g. No heat").performTextInput("Leaking water heater")
    compose.onNodeWithText("Customer name").performTextInput("Pat")
    compose.onNodeWithText("Create job").performScrollTo().performClick()
    waitForText("Start visit")
    compose.onNodeWithText("Leaking water heater").assertIsDisplayed()
    compose.onNodeWithContentDescription("Back").performClick()

    // Settings: wake phrase override is saved.
    waitForText("Today")
    compose.onNodeWithContentDescription("Settings").performClick()
    waitForText("Video source")
    compose.onNodeWithText("Wake phrase (blank = company default)").performScrollTo().performTextReplacement("tech assist")
    compose.onNodeWithText("Save").performScrollTo().performClick()
    val app = ApplicationProvider.getApplicationContext<FieldPartnerApp>()
    assertEquals("tech assist", app.settings.current.wakePhraseOverride)
    compose.onNodeWithText("Sign out").performScrollTo().performClick()
    waitForText("Sign in")

    val seen = synchronized(requests) { requests.toList() }
    assertTrue(seen.toString(), seen.contains("POST /auth/login"))
    assertTrue(seen.toString(), seen.contains("POST /jobs"))
    assertTrue(seen.toString(), seen.any { it == "GET /jobs" })
  }

  companion object {
    const val USER = """{"id":"u1","name":"Sam Tech","email":"tech@fieldpartner.local","role":"tech","trades":["hvac"],"companyId":"c1"}"""
    const val COMPANY = """{"id":"c1","name":"Demo Field Services","wake_phrase":"hey dispatch","co_stop_ppm":35,"diagnostic_fee_cents":8900}"""
    const val JOB = """{"id":"j1","company_id":"c1","trade":"hvac","title":"No heat upstairs","complaint":"Furnace runs then shuts off","address":"12 Elm St","scheduled_at":"2026-11-20T15:00:00Z","status":"scheduled","customer_name":"Alex Rivera","customer_phone":"+15555550111"}"""
  }
}
