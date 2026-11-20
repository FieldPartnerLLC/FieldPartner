package com.fieldpartner.app.glasses

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the connection to the Meta glasses through the Wearables Device Access Toolkit:
 * registration with the Meta AI app, the device session, and the camera stream.
 * Decoded frames go to [onFrame]. With [useSimulated], MockDeviceKit plays a video
 * file as if it were the glasses camera, so the whole pipeline runs in an emulator.
 */
class GlassesManager(private val app: Context) {
  data class Status(
    val registration: String = "unknown",
    val deviceCount: Int = 0,
    val deviceName: String? = null,
    val connected: Boolean = false,
    val battery: Int? = null,
    val sessionState: String = "idle",
    val streamState: String = "stopped",
    val frames: Long = 0,
    val lastError: String? = null,
    val simulated: Boolean = false,
  ) {
    val isRegistered: Boolean
      get() = registration == RegistrationState.REGISTERED.name
    val isStreaming: Boolean
      get() = streamState == StreamState.STREAMING.name
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
  private val _status = MutableStateFlow(Status())
  val status: StateFlow<Status> = _status.asStateFlow()

  /** Called for every decoded frame, on a background thread. The buffer is only valid during the call. */
  @Volatile var onFrame: ((ByteBuffer, Int, Int) -> Unit)? = null

  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private val jobs = mutableListOf<Job>()
  private var watchdog: Job? = null
  private var initialized = false
  private var mockGlasses: MockGlasses? = null
  @Volatile private var lastFrameAt = 0L
  private var restarts = 0
  @Volatile private var wantStream = false

  fun initialize() {
    if (initialized) return
    Wearables.initialize(app).onFailure { err, _ -> setError("Glasses toolkit failed to start: ${err.description}") }
    initialized = true
    scope.launch { Wearables.registrationState.collect { s -> _status.update { it.copy(registration = s.name) } } }
    scope.launch {
      Wearables.devices.collect { ids ->
        _status.update { it.copy(deviceCount = ids.size) }
        ids.firstOrNull()?.let { id ->
          Wearables.devicesMetadata[id]?.let { flow ->
            scope.launch {
              flow.collect { d ->
                _status.update { it.copy(deviceName = d.name, connected = d.linkState == LinkState.CONNECTED, battery = d.batteryLevel.takeIf { b -> b in 0..100 }) }
              }
            }
          }
        }
      }
    }
  }

  /** Opens the Meta AI app so the technician can connect this app to their glasses. */
  fun register(activity: Activity) {
    initialize()
    Wearables.startRegistration(activity)
  }

  fun unregister(activity: Activity) = Wearables.startUnregistration(activity)

  suspend fun cameraPermissionGranted(): Boolean =
    Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull() == PermissionStatus.Granted

  /**
   * Simulated glasses for the emulator and demos. [videoUri] must be an H.265 (HEVC)
   * file; null uses the phone camera as the "glasses" camera.
   */
  fun useSimulated(videoUri: Uri?) {
    val kit = MockDeviceKit.getInstance(app)
    if (!kit.isEnabled) kit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = true, isDevMode = true))
    initialize()
    val glasses =
      mockGlasses
        ?: kit.pairGlasses(GlassesModel.RAYBAN_META).fold(onSuccess = { it }, onFailure = { err, _ ->
          setError("Simulated glasses failed: ${err.description}")
          null
        })
        ?: return
    mockGlasses = glasses
    glasses.powerOn()
    glasses.unfold()
    glasses.don()
    glasses.setBatteryLevel(87)
    if (videoUri != null) glasses.services.camera.setCameraFeed(videoUri)
    else glasses.services.camera.setCameraFeed(com.meta.wearable.dat.mockdevice.api.camera.CameraFacing.BACK)
    _status.update { it.copy(simulated = true, lastError = null) }
  }

  fun stopSimulated() {
    val kit = MockDeviceKit.getInstance(app)
    mockGlasses?.let { runCatching { kit.unpairDevice(it) } }
    mockGlasses = null
    if (kit.isEnabled) kit.disable()
    _status.update { it.copy(simulated = false) }
  }

  /** Starts the device session and the camera stream. Safe to call twice. */
  fun startStreaming() {
    initialize()
    wantStream = true
    if (session != null) return
    restarts = 0
    openSession()
    watchdog?.cancel()
    watchdog =
      scope.launch {
        // Wi-Fi Direct between phone and glasses drops in busy RF. Restart after a 10 s stall, up to 3 times.
        while (wantStream) {
          delay(5_000)
          val stalled = lastFrameAt > 0 && System.currentTimeMillis() - lastFrameAt > 10_000
          if (stalled && restarts < 3) {
            restarts++
            Log.w(TAG, "glasses stream stalled, restart $restarts")
            closeSession()
            delay(1_000)
            if (wantStream) openSession()
          }
        }
      }
  }

  fun stopStreaming() {
    wantStream = false
    watchdog?.cancel()
    watchdog = null
    closeSession()
  }

  private fun openSession() {
    if (!_status.value.isRegistered) {
      setError("Connect the glasses first: Settings > Connect Meta glasses.")
      return
    }
    Wearables.createSession(AutoDeviceSelector()).fold(
      onSuccess = { s ->
        session = s
        jobs += scope.launch { s.errors.collect { e -> setError(e.description) } }
        jobs +=
          scope.launch {
            s.state.collect { st ->
              _status.update { it.copy(sessionState = st.name) }
              if (st == DeviceSessionState.STARTED && camera == null) addCamera(s)
              if (st == DeviceSessionState.STOPPED) {
                camera = null
                _status.update { it.copy(streamState = StreamState.STOPPED.name) }
              }
            }
          }
        s.start()
      },
      onFailure = { e, _ -> setError("Glasses session failed: ${e.description}") },
    )
  }

  private fun addCamera(s: DeviceSession) {
    s.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15)).fold(
      onSuccess = { cam ->
        camera = cam
        val stream = cam.stream
        jobs += scope.launch { stream.state.collect { st -> _status.update { it.copy(streamState = st.name) } } }
        jobs += scope.launch { stream.errorStream.collect { e -> setError(e.description) } }
        jobs +=
          scope.launch(frameDispatcher) {
            var n = 0L
            stream.videoStream.collect { frame ->
              lastFrameAt = System.currentTimeMillis()
              if (!frame.isCompressed) onFrame?.invoke(frame.buffer, frame.width, frame.height)
              n++
              if (n % 15 == 0L) _status.update { it.copy(frames = n) }
            }
          }
        stream.start().onFailure { e, _ -> setError("Camera stream failed: ${e.description}") }
      },
      onFailure = { e, _ -> setError("Glasses camera unavailable: ${e.description}") },
    )
  }

  private fun closeSession() {
    jobs.forEach { it.cancel() }
    jobs.clear()
    runCatching { camera?.stop() }
    camera = null
    runCatching { session?.stop() }
    session = null
    lastFrameAt = 0
    _status.update { it.copy(sessionState = "idle", streamState = StreamState.STOPPED.name) }
  }

  private fun setError(msg: String) {
    Log.w(TAG, msg)
    _status.update { it.copy(lastError = msg) }
  }

  companion object {
    private const val TAG = "Glasses"
  }
}
