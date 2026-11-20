package com.fieldpartner.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldpartner.app.MainActivity
import java.io.File
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the main screens to PNG (build/screens/) so the UI can be reviewed without a device. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class ScreenshotTest {
  private val server =
    MockWebServer().apply {
      dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: ""
            fun ok(b: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(b)
            return when {
              path == "/auth/login" -> ok("""{"token":"T","user":${ScreenFlowTest.USER},"company":${ScreenFlowTest.COMPANY}}""")
              path == "/me" -> ok("""{"user":${ScreenFlowTest.USER},"company":${ScreenFlowTest.COMPANY},"trades":[{"id":"hvac","name":"HVAC","profile":{"preWorkReadings":[{"key":"co","label":"CO ambient","unit":"ppm"},{"key":"gas","label":"Gas leak check"}],"stopRules":["CO above 35 ppm","Gas smell at the appliance"]}}]}""")
              path.startsWith("/jobs") -> ok("""{"jobs":[${ScreenFlowTest.JOB},{"id":"j3","company_id":"c1","trade":"plumbing","title":"Water heater leaking","address":"88 Oak Ave, Lewis Center OH","scheduled_at":"2026-11-20T18:30:00Z","status":"scheduled","customer_name":"Pat Lee"}]}""")
              else -> MockResponse().setResponseCode(404)
            }
          }
        }
      start()
    }

  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  private val out = File("build/screens").also { it.mkdirs() }

  private fun shot(name: String) {
    compose.waitForIdle()
    val view = compose.activity.window.decorView
    val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    compose.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
    File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  private fun waitText(t: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty() }

  @Test fun captureMainScreens() {
    compose.runOnUiThread {
      compose.activity.vm.logout()
      compose.activity.vm.app.settings.setupDoneFor = null
    }
    compose.onNodeWithTag("server").performTextReplacement(server.url("/").toString())
    compose.onNodeWithTag("email").performTextInput("tech@fieldpartner.local")
    compose.onNodeWithTag("password").performTextInput("secret")
    shot("01-login")
    compose.onNodeWithTag("signin").performScrollTo().performClick()
    waitText("Setup 1 of 4")
    shot("02-setup-permissions")
    compose.onNodeWithTag("permissionsNext").performScrollTo()
    compose.runOnUiThread { compose.activity.vm.app.settings.update { it.copy(videoSource = com.fieldpartner.app.VideoSource.GLASSES) } }
    org.robolectric.Shadows.shadowOf(compose.activity.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
    compose.activity.runOnUiThread { }
    compose.onNodeWithTag("skipSetup").performScrollTo()
    compose.runOnUiThread { compose.activity.vm.finishSetup() }
    waitText("No heat upstairs")
    shot("03-jobs")
    compose.onNodeWithTag("haveInvite", useUnmergedTree = true).let { }
  }

  @Test fun captureSetupSteps() {
    org.robolectric.Shadows.shadowOf(compose.activity.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.BLUETOOTH_CONNECT)
    compose.runOnUiThread {
      compose.activity.vm.logout()
      compose.activity.vm.app.settings.setupDoneFor = null
    }
    compose.onNodeWithTag("server").performTextReplacement(server.url("/").toString())
    compose.onNodeWithTag("email").performTextInput("tech@fieldpartner.local")
    compose.onNodeWithTag("password").performTextInput("secret")
    compose.onNodeWithTag("signin").performScrollTo().performClick()
    waitText("Setup 1 of 4")
    compose.onNodeWithTag("permissionsNext").performScrollTo().performClick()
    waitText("Setup 2 of 4")
    compose.onNodeWithTag("src_GLASSES").performClick()
    shot("04-setup-camera")
    compose.onNodeWithTag("cameraNext").performScrollTo().performClick()
    waitText("Setup 3 of 4")
    shot("05-setup-connect")
    compose.runOnUiThread { compose.activity.vm.finishSetup() }
    waitText("No heat upstairs")
    compose.onNodeWithTag("haveInvite", useUnmergedTree = true)
    compose.runOnUiThread { compose.activity.vm.push(Screen.JobDetail(com.fieldpartner.app.core.FpJson.decodeFromString(com.fieldpartner.app.core.Job.serializer(), ScreenFlowTest.JOB))) }
    waitText("Start visit")
    shot("06-job-detail")
    compose.runOnUiThread { compose.activity.vm.logout() }
    waitText("I have an invite code")
    compose.runOnUiThread { compose.activity.vm.push(Screen.Join) }
    waitText("Join your company")
    shot("07-join")
  }
}
