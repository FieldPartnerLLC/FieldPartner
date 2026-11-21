package com.fieldpartner.app.visit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.StatFs
import android.util.Base64
import android.util.Log
import com.fieldpartner.app.AppSettings
import com.fieldpartner.app.FieldPartnerApp
import com.fieldpartner.app.VideoSource
import com.fieldpartner.app.record.AudioSegmentRecorder
import com.fieldpartner.app.audio.Earcon
import com.fieldpartner.app.audio.LocalMic
import com.fieldpartner.app.audio.TranscriptEngine
import com.fieldpartner.app.core.AgentCommand
import com.fieldpartner.app.core.AgentEvent
import com.fieldpartner.app.core.ApiException
import com.fieldpartner.app.core.CommandParser
import com.fieldpartner.app.core.CompanyInfo
import com.fieldpartner.app.core.ConsentBody
import com.fieldpartner.app.core.EventQueue
import com.fieldpartner.app.core.Job
import com.fieldpartner.app.core.Visit
import com.fieldpartner.app.core.VisitEvent
import com.fieldpartner.app.core.VoiceCommand
import com.fieldpartner.app.live.LiveSession
import com.fieldpartner.app.record.VideoSegmentRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs one visit end to end: glasses, recording, transcript, the AI partner, the live
 * call, consent, escalation and the hand-off to the report. Android twin of the iOS
 * VisitController; both speak the same protocol to the same backend and agent.
 */
class VisitController(private val app: FieldPartnerApp, val visit: Visit, val job: Job, private val company: CompanyInfo?) {
  enum class Phase { PREPARING, PRE_VISIT, ACTIVE, NO_CAMERA, ENDING, ENDED, DECLINED }

  data class Notice(val id: String = UUID.randomUUID().toString(), val kind: Kind, val title: String, val detail: String, val at: Long = System.currentTimeMillis()) {
    enum class Kind { INFO, HINT, SAFETY, TOOL, ESCALATION, ERROR }
  }

  data class Reading(val name: String, val value: String, val unit: String)

  data class Preflight(
    val videoReady: Boolean,
    val videoDetail: String,
    val glassesBattery: Int?,
    val phoneBattery: Int,
    val freeStorageGb: Double,
    val server: Boolean,
    val voiceCommands: Boolean,
    val micPermission: Boolean,
  )

  data class Ui(
    val phase: Phase = Phase.PREPARING,
    val recording: Boolean = false,
    val cameraPaused: Boolean = false,
    val consentChoice: String? = null,
    val notices: List<Notice> = emptyList(),
    val transcript: List<String> = emptyList(),
    val livePartial: String = "",
    val readings: List<Reading> = emptyList(),
    val pendingHints: Int = 0,
    val safetyAlert: String? = null,
    val escalation: String = "none", // none, requested, paging, joined, unanswered
    val estimateDraft: Map<String, String>? = null,
    val queuedEvents: Int = 0,
    val preflight: Preflight? = null,
    val errorBanner: String? = null,
    val voiceStatus: String? = null,
  )

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val settings: AppSettings = app.settings
  private val api = app.api
  private val feedback = app.feedback
  val live = LiveSession(app)
  val glasses = app.glasses
  private val mediaDir = File(app.filesDir, "visits/${visit.id}").also { it.mkdirs() }
  private val events = EventQueue(visit.id, File(app.filesDir, "queues"))
  private val transcriptEngine = TranscriptEngine(app)
  private val localMic = LocalMic(app)
  private val videoRecorder = VideoSegmentRecorder(mediaDir, "visit")
  private val audioRecorder = AudioSegmentRecorder(mediaDir, "visit")
  private val parser =
    CommandParser(settings.current.wakePhraseOverride.ifBlank { company?.wake_phrase ?: "hey dispatch" })
  private val source: VideoSource = settings.current.videoSource
  private var started = false
  private var wakeChimed = false
  private var liveRetry: kotlinx.coroutines.Job? = null
  private var flushLoop: kotlinx.coroutines.Job? = null
  private val transcriptLines = ArrayList<String>()

  private val _ui = MutableStateFlow(initialUi())
  val ui: StateFlow<Ui> = _ui.asStateFlow()

  val wakePhrase: String
    get() = parser.wakePhrase

  private fun initialUi(): Ui {
    val c = visit.consent_choice ?: return Ui()
    val phase =
      when (c) {
        "video_audio" -> Phase.ACTIVE
        "declined_service" -> Phase.DECLINED
        else -> Phase.NO_CAMERA
      }
    return Ui(consentChoice = c, phase = phase)
  }

  private val cameraAllowed: Boolean
    get() = !_ui.value.cameraPaused && (_ui.value.consentChoice == null || _ui.value.consentChoice == "video_audio")

  // ---- Preflight ----------------------------------------------------------------------------

  suspend fun checkPreflight() {
    val bm = app.getSystemService(BatteryManager::class.java)
    val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 100
    val free = runCatching { StatFs(app.filesDir.path).availableBytes / 1e9 }.getOrDefault(0.0)
    val server = runCatching { api.me() }.isSuccess
    if (source == VideoSource.SIMULATED_GLASSES) glasses.useSimulated(simulatedVideoUri())
    else if (glasses.status.value.simulated) glasses.stopSimulated()
    val glassesCamera = source != VideoSource.GLASSES || runCatching { glasses.cameraPermissionGranted() }.getOrDefault(false)
    val gs = glasses.status.value
    val (videoReady, videoDetail) =
      when (source) {
        VideoSource.PHONE_CAMERA -> (app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) to "Phone camera"
        VideoSource.SIMULATED_GLASSES -> gs.isRegistered to "Simulated glasses (demo video)"
        VideoSource.GLASSES ->
          (gs.isRegistered && gs.deviceCount > 0 && glassesCamera) to
            when {
              !gs.isRegistered -> "Not connected. Settings > Connect Meta glasses"
              gs.deviceCount == 0 -> "No glasses found. Open the case, put them on"
              !glassesCamera -> "Glasses camera not allowed. Settings > Allow glasses camera"
              else -> gs.deviceName ?: "Meta glasses"
            }
      }
    _ui.update {
      it.copy(
        preflight =
          Preflight(
            videoReady = videoReady,
            videoDetail = videoDetail,
            glassesBattery = if (source == VideoSource.PHONE_CAMERA) null else gs.battery,
            phoneBattery = battery,
            freeStorageGb = free,
            server = server,
            voiceCommands = transcriptEngine.available,
            micPermission = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
          )
      )
    }
  }

  /** The bundled demo video unless the user picked their own in Settings. */
  private fun simulatedVideoUri(): Uri {
    settings.current.simulatedVideoUri.takeIf { it.isNotBlank() }?.let { return Uri.parse(it) }
    val f = File(app.cacheDir, "demo-glasses.mp4")
    if (!f.exists() || f.length() == 0L) app.assets.open("demo-glasses.mp4").use { input -> f.outputStream().use { input.copyTo(it) } }
    return Uri.fromFile(f)
  }

  // ---- Start --------------------------------------------------------------------------------

  fun start() {
    if (started) return
    started = true
    VisitService.start(app, job.title, source == VideoSource.PHONE_CAMERA)

    // Glasses frames fan out to the live call and the recorder.
    glasses.onFrame = { buf, w, h ->
      live.pushGlassesFrame(buf, w, h)
      videoRecorder.appendI420(buf, w, h)
    }
    live.onLocalCameraFrame = { frame ->
      val i420 = frame.buffer.toI420()
      if (i420 != null) {
        videoRecorder.appendPlanes(i420.dataY, i420.strideY, i420.dataU, i420.strideU, i420.dataV, i420.strideV, i420.width, i420.height)
        i420.release()
      }
    }
    if (source != VideoSource.PHONE_CAMERA) glasses.startStreaming()

    transcriptEngine.onPartial = { text -> scope.launch { handlePartial(text) } }
    transcriptEngine.onFinal = { text -> scope.launch { handleFinal(text) } }
    transcriptEngine.onStatus = { st, msg -> scope.launch { _ui.update { it.copy(voiceStatus = if (st == TranscriptEngine.Status.RUNNING) null else msg) } } }
    transcriptEngine.start(settings.current.language)

    videoRecorder.onSegmentFinished = { f -> enqueueUpload(f, "video", "video/mp4") }
    audioRecorder.onSegmentFinished = { f -> enqueueUpload(f, "audio", "audio/mp4") }

    live.onAgentEvent = { e -> handleAgentEvent(e) }
    live.onAIUtterance = { text -> appendTranscript("AI: $text", log = false) }

    startRecording()
    if (_ui.value.phase == Phase.PREPARING) _ui.update { it.copy(phase = Phase.PRE_VISIT) }
    log("state", mapOf("state" to "pre_visit"), actor = "system")
    log("device", mapOf("platform" to "android", "model" to android.os.Build.MODEL, "os" to android.os.Build.VERSION.RELEASE, "videoSource" to source.name.lowercase()), actor = "system")
    startFlushLoop()
    scope.launch {
      connectLive()
      announce("Recording started. Your partner is standing by. Get consent at the door.")
    }
  }

  private fun startRecording() {
    val c = _ui.value.consentChoice
    if (c == "no_recording") return
    if (c != "audio_only") videoRecorder.start()
    audioRecorder.start()
    _ui.update { it.copy(recording = true) }
    feedback.play(Earcon.RECORDING_ON)
  }

  private suspend fun connectLive() {
    try {
      val ticket = api.liveTicket(visit.id)
      localMic.stop()
      live.connect(ticket, phoneCamera = source == VideoSource.PHONE_CAMERA)
      if (live.ui.value.state != LiveSession.State.CONNECTED) throw ApiException.Server(0, "live", live.ui.value.error ?: "Live call failed")
      live.send(AgentCommand.camera(cameraAllowed))
      if (!cameraAllowed) live.setVideoMuted(true)
      watchLive()
    } catch (e: Exception) {
      // Keep working offline: local mic for transcript and recording, retry the call.
      localMic.start()
      notify(Notice.Kind.ERROR, "Partner offline", e.message ?: "Live call unavailable")
      scheduleLiveRetry()
    }
  }

  private var liveWatch: kotlinx.coroutines.Job? = null

  /** If the call drops for good mid-visit, fall back to the local mic and reconnect. */
  private fun watchLive() {
    liveWatch?.cancel()
    liveWatch =
      scope.launch {
        live.ui.collect { s ->
          if (s.state == LiveSession.State.FAILED && _ui.value.phase !in setOf(Phase.ENDING, Phase.ENDED, Phase.DECLINED)) {
            live.disconnect()
            localMic.start()
            notify(Notice.Kind.ERROR, "Call dropped", "Still recording. Reconnecting in 15 seconds.")
            scheduleLiveRetry()
            liveWatch?.cancel()
          }
        }
      }
  }

  private fun scheduleLiveRetry() {
    liveRetry?.cancel()
    liveRetry =
      scope.launch {
        delay(15_000)
        val p = _ui.value.phase
        if (p == Phase.ENDED || p == Phase.ENDING || p == Phase.DECLINED) return@launch
        if (live.ui.value.state != LiveSession.State.CONNECTED) connectLive()
      }
  }

  // ---- Transcript and voice commands --------------------------------------------------------

  private fun handlePartial(text: String) {
    _ui.update { it.copy(livePartial = text) }
    if (!wakeChimed && parser.wakeHeard(text)) {
      wakeChimed = true
      feedback.play(Earcon.WAKE)
    }
  }

  private suspend fun handleFinal(text: String) {
    _ui.update { it.copy(livePartial = "") }
    wakeChimed = false
    appendTranscript(text, log = true)
    parser.parse(text)?.let { perform(it) }
  }

  private fun appendTranscript(line: String, log: Boolean) {
    transcriptLines.add(line)
    _ui.update { val t = (it.transcript + line); it.copy(transcript = if (t.size > 400) t.takeLast(400) else t) }
    if (log) log("transcript", mapOf("speaker" to "room", "text" to line))
  }

  /** Typed question: for loud sites, the emulator, or Android 12 phones without voice commands. */
  suspend fun askTyped(text: String) {
    val t = text.trim()
    if (t.isEmpty()) return
    appendTranscript("You (typed): $t", log = false)
    log("question", mapOf("text" to t, "typed" to true))
    perform(VoiceCommand.Ask(t))
  }

  suspend fun perform(command: VoiceCommand) {
    when (command) {
      is VoiceCommand.Ask -> {
        log("wake", mapOf("text" to command.text))
        if (live.ui.value.state != LiveSession.State.CONNECTED) {
          announce("Your partner is offline right now. Keep working, I'm still recording.")
          return
        }
        live.send(if (command.text.isEmpty()) AgentCommand.listen() else AgentCommand.ask(command.text))
      }
      VoiceCommand.Stop -> live.send(AgentCommand.stop())
      VoiceCommand.ContinueLast -> live.send(AgentCommand.continueLast())
      VoiceCommand.PauseCamera -> setCameraPaused(true)
      VoiceCommand.ResumeCamera -> setCameraPaused(false)
      VoiceCommand.PlayHint -> {
        _ui.update { it.copy(pendingHints = maxOf(0, it.pendingHints - 1)) }
        live.send(AgentCommand.hintPlay())
      }
      VoiceCommand.Acknowledge -> {
        _ui.value.safetyAlert?.let { alert ->
          log("safety", mapOf("level" to "acknowledged", "reason" to alert))
          _ui.update { it.copy(safetyAlert = null) }
        }
      }
      VoiceCommand.CallExpert -> escalate("Technician asked for help by voice")
      VoiceCommand.StopRecording -> revokeRecording()
      is VoiceCommand.Disputed -> {
        log("disputed", mapOf("text" to command.text))
        live.send(AgentCommand.disputed(command.text))
      }
    }
  }

  /** Push to talk from the on-screen button or a Bluetooth clip button. */
  fun talk() {
    feedback.play(Earcon.WAKE)
    scope.launch { perform(VoiceCommand.Ask("")) }
  }

  fun stopTalking() {
    feedback.play(Earcon.CLOSE)
    scope.launch { perform(VoiceCommand.Stop) }
  }

  // ---- Agent events -------------------------------------------------------------------------

  private fun handleAgentEvent(e: AgentEvent) {
    when (e) {
      is AgentEvent.AssistantState -> if (e.state == "idle" && e.reason == "follow_up_window_closed") feedback.play(Earcon.CLOSE)
      is AgentEvent.Hint -> {
        _ui.update { it.copy(pendingHints = it.pendingHints + 1) }
        feedback.play(Earcon.HINT)
        notify(Notice.Kind.HINT, "Tip waiting", e.reason)
      }
      is AgentEvent.Safety -> {
        _ui.update { it.copy(safetyAlert = e.reason) }
        feedback.play(Earcon.ALERT)
        feedback.buzz(strong = true)
        notify(Notice.Kind.SAFETY, "STOP", e.reason)
      }
      is AgentEvent.Tool ->
        when (e.name) {
          "save_reading" -> {
            val r = Reading(e.data["name"].orEmpty(), e.data["value"].orEmpty(), e.data["unit"].orEmpty())
            _ui.update { it.copy(readings = it.readings + r) }
            notify(Notice.Kind.TOOL, "Reading saved", "${r.name}: ${r.value} ${r.unit}".trim())
          }
          "save_equipment" -> notify(Notice.Kind.TOOL, "Equipment saved", e.data.values.joinToString(" "))
          "draft_estimate" -> {
            _ui.update { it.copy(estimateDraft = e.data) }
            notify(Notice.Kind.TOOL, "Estimate draft ready", e.data["repair"].orEmpty())
          }
          "add_note" -> notify(Notice.Kind.TOOL, "Note saved", e.data["text"].orEmpty())
          "senior_expert" ->
            when (e.data["status"]) {
              "asking" -> notify(Notice.Kind.INFO, "Checking with the senior expert", e.data["question"].orEmpty())
              "answered" -> notify(Notice.Kind.TOOL, "Senior expert", e.data["answer"].orEmpty())
              else -> notify(Notice.Kind.INFO, "Senior expert not available", "Using general guidance.")
            }
        }
      is AgentEvent.Escalation -> {
        e.status?.let { s -> _ui.update { it.copy(escalation = s) } }
        e.paged?.let { notify(Notice.Kind.ESCALATION, "Paging $it", e.reason.orEmpty()) }
      }
      is AgentEvent.Expert ->
        if (e.present) {
          _ui.update { it.copy(escalation = "joined") }
          announce("Your expert is on the line.")
        }
      is AgentEvent.Watcher -> notify(Notice.Kind.ERROR, "Hazard watch offline", "Background safety check can't reach the AI. Keep using your meters.")
      is AgentEvent.Unknown -> {}
    }
  }

  // ---- Consent ------------------------------------------------------------------------------

  suspend fun submitConsent(choice: String, signerName: String, signerRole: String, privateAreasOk: Boolean, signature: Bitmap?, language: String): Boolean {
    val loc = currentLocation()
    val body =
      ConsentBody(
        kind = "service_and_recording",
        choice = choice,
        privateAreasOk = privateAreasOk,
        signerName = signerName.trim().ifEmpty { null },
        signerRole = signerRole,
        language = language,
        lat = loc?.first,
        lng = loc?.second,
        signaturePngBase64 = signature?.let { pngBase64(it) },
      )
    try {
      api.consent(visit.id, body)
    } catch (e: Exception) {
      _ui.update { it.copy(errorBanner = "Consent not saved: ${e.message} Try again.") }
      return false
    }
    _ui.update { it.copy(consentChoice = choice, errorBanner = null) }
    when (choice) {
      "video_audio" -> {
        _ui.update { it.copy(phase = Phase.ACTIVE) }
        live.send(AgentCommand.camera(true))
        live.setVideoMuted(false)
        announce("Consent saved. Video on.")
      }
      "audio_only" -> {
        _ui.update { it.copy(phase = Phase.NO_CAMERA) }
        stopVideoRecording()
        live.setVideoMuted(true)
        live.send(AgentCommand.camera(false))
        announce("Consent saved. Audio only.")
      }
      "no_recording" -> {
        _ui.update { it.copy(phase = Phase.NO_CAMERA, recording = false) }
        stopVideoRecording()
        withContext(Dispatchers.IO) { audioRecorder.stop() }
        live.setVideoMuted(true)
        live.send(AgentCommand.camera(false))
        feedback.play(Earcon.RECORDING_OFF)
        announce("Consent saved. Recording off.")
      }
      "declined_service" -> {
        _ui.update { it.copy(phase = Phase.DECLINED) }
        end()
      }
    }
    return true
  }

  private suspend fun stopVideoRecording() = withContext(Dispatchers.IO) { videoRecorder.stop() }

  suspend fun revokeRecording() {
    runCatching { api.consent(visit.id, ConsentBody(kind = "recording_revoked")) }
    _ui.update { it.copy(consentChoice = "no_recording", phase = Phase.NO_CAMERA, recording = false) }
    stopVideoRecording()
    withContext(Dispatchers.IO) { audioRecorder.stop() }
    live.setVideoMuted(true)
    live.send(AgentCommand.camera(false))
    feedback.play(Earcon.RECORDING_OFF)
    announce("Recording is off.")
  }

  // ---- Camera privacy -----------------------------------------------------------------------

  suspend fun setCameraPaused(paused: Boolean) {
    if (paused == _ui.value.cameraPaused) return
    _ui.update { it.copy(cameraPaused = paused) }
    if (paused) {
      videoRecorder.pause()
      live.setVideoMuted(true)
      log("pause", emptyMap())
      feedback.play(Earcon.RECORDING_OFF)
    } else {
      val c = _ui.value.consentChoice
      if (c == null || c == "video_audio") {
        videoRecorder.start()
        live.setVideoMuted(false)
      }
      log("resume", emptyMap())
      feedback.play(Earcon.RECORDING_ON)
    }
    live.send(AgentCommand.camera(cameraAllowed))
  }

  // ---- Escalation ---------------------------------------------------------------------------

  suspend fun escalate(reason: String) {
    _ui.update { it.copy(escalation = "requested") }
    announce("Calling your expert.")
    try {
      val r = api.escalate(visit.id, reason)
      _ui.update { it.copy(escalation = r.status) }
      r.paged?.let { notify(Notice.Kind.ESCALATION, "Paging $it", reason) }
    } catch (e: Exception) {
      _ui.update { it.copy(escalation = "none") }
      notify(Notice.Kind.ERROR, "Couldn't page the expert", "Call them directly. ${e.message}")
    }
  }

  // ---- Readings and notes by hand -----------------------------------------------------------

  fun addReading(name: String, value: String, unit: String) {
    _ui.update { it.copy(readings = it.readings + Reading(name, value, unit)) }
    log("reading", mapOf("name" to name, "value" to value, "unit" to unit))
  }

  fun addNote(text: String) {
    if (text.isBlank()) return
    log("note", mapOf("text" to text.trim()))
    notify(Notice.Kind.TOOL, "Note saved", text.trim())
  }

  fun acknowledgeSafety() = scope.launch { perform(VoiceCommand.Acknowledge) }

  // ---- End ----------------------------------------------------------------------------------

  suspend fun end() {
    val p = _ui.value.phase
    if (p == Phase.ENDING || p == Phase.ENDED) return
    val wasDeclined = p == Phase.DECLINED
    _ui.update { it.copy(phase = Phase.ENDING) }
    live.send(AgentCommand.end())
    transcriptEngine.stop()
    withContext(Dispatchers.IO) {
      videoRecorder.stop()
      audioRecorder.stop()
    }
    localMic.stop()
    _ui.update { it.copy(recording = false) }
    feedback.play(Earcon.RECORDING_OFF)
    log("state", mapOf("state" to "wrap_up"), actor = "system")
    saveTranscriptFile()
    flushEvents()
    try {
      api.endVisit(visit.id)
    } catch (e: Exception) {
      notify(Notice.Kind.ERROR, "Visit end not sent yet", "It will retry. ${e.message}")
      app.pendingEnds.add(visit.id)
    }
    glasses.stopStreaming()
    glasses.onFrame = null
    live.disconnect()
    flushLoop?.cancel()
    liveRetry?.cancel()
    liveWatch?.cancel()
    VisitService.stop(app)
    _ui.update { it.copy(phase = if (wasDeclined) Phase.DECLINED else Phase.ENDED) }
    app.kickUploads()
  }

  /**
   * Called when the controller goes away for good (visit closed, or the app was swiped away
   * mid-visit). Finishes recording files so they still upload, and releases the mic, glasses
   * and the call. The visit stays open on the server and resumes from the job screen.
   */
  fun dispose() {
    transcriptEngine.stop()
    videoRecorder.stop()
    audioRecorder.stop()
    localMic.stop()
    if (_ui.value.phase != Phase.ENDED && _ui.value.phase != Phase.DECLINED) {
      glasses.stopStreaming()
      glasses.onFrame = null
    }
    live.close()
    VisitService.stop(app)
    scope.cancel()
  }

  private suspend fun saveTranscriptFile() {
    val f = File(mediaDir, "transcript.txt")
    withContext(Dispatchers.IO) { f.writeText(transcriptLines.joinToString("\n")) }
    enqueueUpload(f, "transcript", "text/plain")
  }

  // ---- Plumbing -----------------------------------------------------------------------------

  private fun enqueueUpload(f: File, kind: String, type: String) = app.enqueueUpload(visit.id, f, kind, type)

  private fun log(type: String, data: Map<String, Any?>, actor: String = "tech") {
    val e = VisitEvent.create(type, data, actor)
    scope.launch(Dispatchers.IO) { events.add(e) }
  }

  private fun startFlushLoop() {
    flushLoop =
      scope.launch {
        while (isActive) {
          delay(3_000)
          flushEvents()
        }
      }
  }

  private suspend fun flushEvents() {
    withContext(Dispatchers.IO) { events.flush { batch -> api.postEvents(visit.id, batch) } }
    val n = events.count()
    _ui.update { it.copy(queuedEvents = n) }
  }

  private fun notify(kind: Notice.Kind, title: String, detail: String) {
    _ui.update { it.copy(notices = (listOf(Notice(kind = kind, title = title, detail = detail)) + it.notices).take(50)) }
  }

  /** Short spoken status in the glasses. Never used while the AI is talking. */
  fun announce(text: String) {
    if (live.ui.value.assistant == "speaking") return
    feedback.say(text, settings.current.aiVoiceVolume)
  }

  private fun pngBase64(b: Bitmap): String {
    val out = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.PNG, 100, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
  }

  /** One-shot location for the consent record. Null quickly if denied or slow. */
  private suspend fun currentLocation(): Pair<Double, Double>? {
    val granted =
      app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    val lm = app.getSystemService(LocationManager::class.java) ?: return null
    val provider =
      listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        ?: return null
    return try {
      withTimeoutOrNull(TimeUnit.SECONDS.toMillis(5)) {
        suspendCancellableCoroutine { cont ->
          val signal = android.os.CancellationSignal()
          cont.invokeOnCancellation { signal.cancel() }
          @Suppress("MissingPermission")
          lm.getCurrentLocation(provider, signal, app.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc?.let { it.latitude to it.longitude }) }
        }
      }
    } catch (e: SecurityException) {
      Log.w("Visit", "location denied: ${e.message}")
      null
    }
  }
}
