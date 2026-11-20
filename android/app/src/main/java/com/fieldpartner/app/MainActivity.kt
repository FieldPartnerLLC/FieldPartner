package com.fieldpartner.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fieldpartner.app.core.Job
import com.fieldpartner.app.ui.AppViewModel
import com.fieldpartner.app.ui.FpTheme
import com.fieldpartner.app.ui.JobDetailScreen
import com.fieldpartner.app.ui.JobsScreen
import com.fieldpartner.app.ui.JoinScreen
import com.fieldpartner.app.ui.SetupScreen
import com.fieldpartner.app.ui.LoginScreen
import com.fieldpartner.app.ui.ReportScreen
import com.fieldpartner.app.ui.Screen
import com.fieldpartner.app.ui.SettingsScreen
import com.fieldpartner.app.ui.VisitScreen
import com.fieldpartner.app.ui.WalkInScreen
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus

class MainActivity : ComponentActivity() {
  internal val vm: AppViewModel by viewModels()
  private var pendingJob: Job? = null
  var glassesPermissionMessage: ((String) -> Unit)? = null

  /** Android runtime permissions needed before a visit. */
  private val visitPermissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
      val job = pendingJob ?: return@registerForActivityResult
      pendingJob = null
      if (result[Manifest.permission.RECORD_AUDIO] == false) {
        vm.clearError()
        android.widget.Toast.makeText(this, "Microphone permission is required for visits.", android.widget.Toast.LENGTH_LONG).show()
        return@registerForActivityResult
      }
      vm.startVisit(job)
    }

  private var permsCallback: ((Map<String, Boolean>) -> Unit)? = null
  private val anyPermissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
      permsCallback?.invoke(r)
      permsCallback = null
    }

  /** Asks Android for runtime permissions, then calls [done] with the result. */
  fun requestPermissions(perms: Array<String>, done: (Map<String, Boolean>) -> Unit) {
    permsCallback = done
    anyPermissions.launch(perms)
  }

  /** Glasses camera permission is granted in the Meta AI app, not by Android. */
  private val glassesCameraPermission =
    registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
      val msg =
        result.fold(
          onSuccess = { if (it == PermissionStatus.Granted) "Glasses camera allowed." else "Glasses camera not allowed. Allow it in the Meta AI app." },
          onFailure = { e, _ -> "Permission request failed: ${e.description}" },
        )
      glassesPermissionMessage?.invoke(msg)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handleIntent(intent)
    setContent {
      FpTheme {
        val state by vm.state.collectAsStateWithLifecycle()
        BackHandler(enabled = state.stack.size > 1 && state.screen !is Screen.Visit) { vm.back() }
        // Back during a visit must not close the app and kill the visit: send the app to the background instead.
        BackHandler(enabled = state.screen is Screen.Visit) { moveTaskToBack(true) }
        when (val s = state.screen) {
          Screen.Login -> LoginScreen(vm)
          Screen.Join -> JoinScreen(vm)
          Screen.Setup -> SetupScreen(vm, this)
          Screen.Jobs -> JobsScreen(vm)
          is Screen.JobDetail -> JobDetailScreen(vm, s.job, onStart = { requestVisit(it) })
          Screen.WalkIn -> WalkInScreen(vm)
          Screen.Settings -> SettingsScreen(vm, this)
          Screen.Visit -> vm.visit?.let { VisitScreen(vm, it) } ?: JobsScreen(vm)
          is Screen.Report -> ReportScreen(vm, s.visitId)
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIntent(intent)
  }

  /** Meta AI returns here after registration (fieldpartner:// deep link). */
  private fun handleIntent(intent: Intent?) {
    if (intent?.data?.scheme == "fieldpartner") {
      runCatching { Wearables.handleIntent(intent) { /* registration requests are accepted by default */ } }
    }
  }

  fun requestVisit(job: Job) {
    pendingJob = job
    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (android.os.Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
    if (vm.app.settings.current.videoSource == VideoSource.PHONE_CAMERA) perms += Manifest.permission.CAMERA
    visitPermissions.launch(perms.toTypedArray())
  }

  fun requestGlassesCamera() = glassesCameraPermission.launch(Permission.CAMERA)

  fun keepScreenOn(on: Boolean) {
    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  /**
   * Bluetooth clip buttons and headset buttons: press to talk, long press to stop.
   * Volume keys only when the tech turned that on (it takes over the volume buttons).
   */
  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    val v = vm.visit
    if (v != null && vm.state.value.screen is Screen.Visit) {
      val clip = keyCode in setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_CAMERA)
      val vol = vm.app.settings.current.volumeKeysAsClip && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
      if (clip || vol) {
        if (event.repeatCount == 0) {
          if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) v.stopTalking() else v.talk()
        }
        return true
      }
    }
    return super.onKeyDown(keyCode, event)
  }
}
