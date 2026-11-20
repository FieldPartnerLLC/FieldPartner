package com.fieldpartner.app.live

import android.content.Context
import android.util.Log
import com.fieldpartner.app.audio.MicBus
import com.fieldpartner.app.core.AgentEvent
import com.fieldpartner.app.core.LiveTicket
import com.fieldpartner.app.glasses.FrameCapturer
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.datastream.StreamTextOptions
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoEncoding
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink

/**
 * One LiveKit room per visit. The phone publishes the glasses video and the
 * technician's microphone. The AI agent and, when called, a human expert join the
 * same room. Commands go to the agent on "fp.cmd"; its events come back on "fp.event".
 * Same protocol as the iOS app.
 */
class LiveSession(private val app: Context) {
  enum class State { IDLE, CONNECTING, CONNECTED, RECONNECTING, FAILED }

  data class Caption(val text: String, val fromAI: Boolean)

  data class Ui(
    val state: State = State.IDLE,
    val error: String? = null,
    val assistant: String = "unknown",
    val agentPresent: Boolean = false,
    val expertPresent: Boolean = false,
    val expertName: String? = null,
    val caption: Caption? = null,
    val localVideoTrack: LocalVideoTrack? = null,
  )

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val _ui = MutableStateFlow(Ui())
  val ui: StateFlow<Ui> = _ui.asStateFlow()

  var onAgentEvent: ((AgentEvent) -> Unit)? = null
  var onAIUtterance: ((String) -> Unit)? = null
  /** Phone-camera frames (glasses frames go to the recorder directly). */
  var onLocalCameraFrame: ((VideoFrame) -> Unit)? = null

  private var room: Room? = null
  private var capturer: FrameCapturer? = null
  private var eventsJob: Job? = null
  private var captionClear: Job? = null
  private var usingPhoneCamera = false
  private val cameraSink = VideoSink { frame -> onLocalCameraFrame?.invoke(frame) }

  val roomOrNull: Room?
    get() = room

  private fun createRoom(): Room =
    LiveKit.create(
      app,
      RoomOptions(adaptiveStream = true, dynacast = false),
      LiveKitOverrides(audioOptions = AudioOptions(javaAudioDeviceModuleCustomizer = { b -> b.setSamplesReadyCallback(MicBus) })),
    )

  suspend fun connect(ticket: LiveTicket, phoneCamera: Boolean) {
    val s = _ui.value.state
    if (s == State.CONNECTING || s == State.CONNECTED || s == State.RECONNECTING) return
    _ui.update { it.copy(state = State.CONNECTING, error = null) }
    usingPhoneCamera = phoneCamera
    val r = room ?: createRoom().also { room = it }
    watch(r)
    registerHandlers(r)
    try {
      r.connect(ticket.url, ticket.token)
      r.localParticipant.setMicrophoneEnabled(true)
      try {
        if (phoneCamera) {
          r.videoTrackCaptureDefaults = LocalVideoTrackOptions(position = CameraPosition.BACK)
          r.localParticipant.setCameraEnabled(true)
          val track = r.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
          track?.addRenderer(cameraSink)
          _ui.update { it.copy(localVideoTrack = track) }
        } else {
          val cap = FrameCapturer()
          val track = r.localParticipant.createVideoTrack(name = "glasses", capturer = cap)
          capturer = cap
          track.startCapture()
          r.localParticipant.publishVideoTrack(
            track,
            VideoTrackPublishOptions(name = "glasses", videoEncoding = VideoEncoding(2_500_000, 15), simulcast = false, source = Track.Source.CAMERA),
          )
          _ui.update { it.copy(localVideoTrack = track) }
        }
      } catch (e: Exception) {
        // Video trouble (no camera in the emulator, permission) degrades to voice only.
        Log.w(TAG, "video publish failed, voice only: ${e.message}")
      }
      _ui.update { it.copy(state = State.CONNECTED) }
      refreshParticipants()
    } catch (e: Exception) {
      Log.w(TAG, "connect failed: ${e.message}")
      _ui.update { it.copy(state = State.FAILED, error = e.message ?: "Could not connect") }
      runCatching { r.disconnect() }
    }
  }

  /** Glasses frame from the DAT stream. Any thread. */
  fun pushGlassesFrame(buf: ByteBuffer, width: Int, height: Int) {
    capturer?.pushI420(buf, width, height)
  }

  val glassesFramesSent: Long
    get() = capturer?.framesSent ?: 0

  /** Privacy pause: the AI and the expert see nothing while muted. */
  fun setVideoMuted(muted: Boolean) {
    room?.localParticipant?.getTrackPublication(Track.Source.CAMERA)?.muted = muted
  }

  suspend fun setMicEnabled(on: Boolean) {
    runCatching { room?.localParticipant?.setMicrophoneEnabled(on) }
  }

  /** Sends one JSON command to the agent. Silently skipped while offline (the caller tells the tech). */
  suspend fun send(json: String): Boolean {
    val r = room ?: return false
    if (_ui.value.state != State.CONNECTED && _ui.value.state != State.RECONNECTING) return false
    return try {
      r.localParticipant.sendText(json, StreamTextOptions(topic = "fp.cmd")).isSuccess
    } catch (e: Exception) {
      Log.w(TAG, "command send failed: ${e.message}")
      false
    }
  }

  fun disconnect() {
    capturer = null
    _ui.value.localVideoTrack?.removeRenderer(cameraSink)
    room?.let { r ->
      runCatching { r.disconnect() }
      runCatching { r.release() }
    }
    room = null
    eventsJob?.cancel()
    eventsJob = null
    _ui.value = Ui()
  }

  fun close() {
    disconnect()
    scope.cancel()
  }

  private fun watch(r: Room) {
    eventsJob?.cancel()
    eventsJob =
      scope.launch {
        r.events.collect { e ->
          when (e) {
            is RoomEvent.ParticipantConnected,
            is RoomEvent.ParticipantDisconnected,
            is RoomEvent.ParticipantAttributesChanged -> refreshParticipants()
            is RoomEvent.Reconnecting -> _ui.update { it.copy(state = State.RECONNECTING) }
            is RoomEvent.Reconnected -> {
              _ui.update { it.copy(state = State.CONNECTED) }
              refreshParticipants()
            }
            is RoomEvent.Disconnected -> {
              if (e.reason != DisconnectReason.CLIENT_INITIATED && _ui.value.state != State.IDLE) {
                _ui.update { it.copy(state = State.FAILED, error = "Connection lost", agentPresent = false, expertPresent = false) }
              }
            }
            else -> {}
          }
        }
      }
  }

  private var handlersOn: Room? = null

  private fun registerHandlers(r: Room) {
    if (handlersOn === r) return
    handlersOn = r
    r.registerTextStreamHandler("fp.event") { receiver, _ ->
      scope.launch {
        val sb = StringBuilder()
        runCatching { receiver.flow.collect { sb.append(it) } }
        val ev = AgentEvent.parse(sb.toString()) ?: return@launch
        when (ev) {
          is AgentEvent.AssistantState -> _ui.update { it.copy(assistant = ev.state) }
          is AgentEvent.Expert -> _ui.update { it.copy(expertPresent = ev.present) }
          else -> {}
        }
        onAgentEvent?.invoke(ev)
      }
    }
    r.registerTextStreamHandler("lk.transcription") { receiver, identity ->
      val sender = r.remoteParticipants[identity]
      val isAgent = sender?.kind == Participant.Kind.AGENT || identity.value.startsWith("agent")
      // The agent also publishes transcripts of the tech's speech; those name our own track.
      val transcribed = receiver.info.attributes["lk.transcribed_track_id"]
      val ourTracks = r.localParticipant.trackPublications.keys
      val fromAI = isAgent && (transcribed == null || transcribed !in ourTracks)
      scope.launch {
        val sb = StringBuilder()
        runCatching {
          receiver.flow.collect {
            sb.append(it)
            showCaption(sb.toString(), fromAI)
          }
        }
        if (fromAI && sb.isNotBlank()) onAIUtterance?.invoke(sb.toString())
      }
    }
  }

  private fun showCaption(text: String, fromAI: Boolean) {
    if (text.isBlank()) return
    _ui.update { it.copy(caption = Caption(text, fromAI)) }
    captionClear?.cancel()
    captionClear =
      scope.launch {
        delay(5_000)
        _ui.update { it.copy(caption = null) }
      }
  }

  private fun refreshParticipants() {
    val r = room ?: return
    val remotes = r.remoteParticipants.values
    val agent = remotes.any { it.kind == Participant.Kind.AGENT || (it.identity?.value?.startsWith("agent") == true) }
    val expert = remotes.firstOrNull { it.identity?.value?.startsWith("expert-") == true }
    _ui.update { it.copy(agentPresent = agent, expertPresent = expert != null, expertName = expert?.name) }
  }

  companion object {
    private const val TAG = "LiveSession"
  }
}
