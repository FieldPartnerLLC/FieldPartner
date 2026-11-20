package com.fieldpartner.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.fieldpartner.app.audio.Feedback
import com.fieldpartner.app.core.ApiClient
import com.fieldpartner.app.core.UploadQueue
import com.fieldpartner.app.core.asUploadApi
import com.fieldpartner.app.glasses.GlassesManager
import io.livekit.android.LiveKit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** App-wide singletons: settings, the API client, the glasses link, the upload queue. */
class FieldPartnerApp : Application() {
  lateinit var settings: AppSettings
    private set
  lateinit var api: ApiClient
    private set
  lateinit var glasses: GlassesManager
    private set
  lateinit var uploads: UploadQueue
    private set
  lateinit var feedback: Feedback
    private set
  lateinit var pendingEnds: PendingEnds
    private set

  val pendingUploads = MutableStateFlow(0)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile private var onWifi = false
  @Volatile private var online = false

  override fun onCreate() {
    super.onCreate()
    settings = AppSettings(this)
    api = ApiClient(settings.current.apiBaseUrl, settings.token)
    glasses = GlassesManager(this)
    uploads = UploadQueue(File(filesDir, "state"))
    feedback = Feedback(this)
    pendingEnds = PendingEnds(File(filesDir, "state/pending-ends.txt"))
    runCatching { LiveKit.init(this) }.onFailure { Log.w("App", "LiveKit init: ${it.message}") }
    // Simulated glasses must enable MockDeviceKit before the toolkit starts, so only start it here for real glasses.
    if (settings.current.videoSource == VideoSource.GLASSES) runCatching { glasses.initialize() }.onFailure { Log.w("App", "glasses init: ${it.message}") }
    watchNetwork()
    kickUploads()
  }

  fun setToken(token: String?) {
    settings.token = token
    api.token = token
  }

  fun setBaseUrl(url: String) {
    settings.update { it.copy(apiBaseUrl = url) }
    api.baseUrl = settings.current.apiBaseUrl
  }

  /** Queues a finished recording file. App scope, so ending or leaving a visit never drops it. */
  fun enqueueUpload(visitId: String, file: File, kind: String, contentType: String) {
    scope.launch {
      uploads.enqueue(visitId, file, kind, contentType)
      kickUploads()
    }
  }

  /** Runs one upload pass (and retries any visit-end calls that failed offline). */
  fun kickUploads() {
    scope.launch {
      if (api.token == null) return@launch
      for (id in pendingEnds.all()) {
        runCatching { api.endVisit(id) }.onSuccess { pendingEnds.remove(id) }
      }
      if (!online) {
        pendingUploads.value = uploads.pendingCount()
        return@launch
      }
      try {
        uploads.process(api.asUploadApi(), onWifi, settings.current.uploadVideoOnCellular)
      } catch (e: Exception) {
        Log.w("Uploads", "pass failed: ${e.message}")
      }
      pendingUploads.value = uploads.pendingCount()
    }
  }

  private fun watchNetwork() {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return
    cm.registerDefaultNetworkCallback(
      object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
          val was = online
          online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
          onWifi = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
          if (online && !was) kickUploads()
        }

        override fun onLost(network: Network) {
          online = false
          onWifi = false
        }
      }
    )
  }
}

/** Visit ids whose /end call failed offline. Retried on the next upload pass. */
class PendingEnds(private val file: File) {
  @Synchronized fun all(): List<String> = if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

  @Synchronized fun add(id: String) {
    file.parentFile?.mkdirs()
    if (id !in all()) file.appendText("$id\n")
  }

  @Synchronized fun remove(id: String) {
    file.writeText(all().filter { it != id }.joinToString("") { "$it\n" })
  }
}
