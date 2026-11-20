package com.fieldpartner.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fieldpartner.app.MainActivity
import com.fieldpartner.app.core.ConsentText
import com.fieldpartner.app.live.LiveSession
import com.fieldpartner.app.visit.VisitController
import com.fieldpartner.app.visit.VisitController.Notice
import com.fieldpartner.app.visit.VisitController.Phase
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.LocalVideoTrack
import kotlinx.coroutines.launch

/**
 * The screen during a visit. Built to be glanced at, not read: the technician's hands
 * are busy and the phone is usually in a pocket. Big buttons, one alert color.
 */
@Composable
fun VisitScreen(vm: AppViewModel, c: VisitController) {
  var preflightDone by remember { mutableStateOf(c.ui.value.phase != Phase.PREPARING) }
  val activity = LocalContext.current as? MainActivity
  DisposableEffect(Unit) {
    activity?.keepScreenOn(true)
    onDispose { activity?.keepScreenOn(false) }
  }
  if (!preflightDone) {
    PreflightScreen(vm, c) {
      preflightDone = true
      c.start()
    }
  } else {
    VisitBody(vm, c)
  }
}

@Composable
private fun PreflightScreen(vm: AppViewModel, c: VisitController, onGo: () -> Unit) {
  val ui by c.ui.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  LaunchedEffect(Unit) { c.checkPreflight() }
  FpScaffold("Before you go in", onBack = { vm.closeVisit(openReport = false) }) { mod ->
    Column(mod.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(c.job.title, style = MaterialTheme.typography.titleLarge)
      val p = ui.preflight
      if (p == null) {
        CircularProgressIndicator()
      } else {
        Check("Video: ${p.videoDetail}", p.videoReady)
        p.glassesBattery?.let { Check("Glasses battery $it%", it >= 20) }
        Check("Phone battery ${p.phoneBattery}%", p.phoneBattery >= 20)
        Check("Free storage ${"%.1f".format(p.freeStorageGb)} GB", p.freeStorageGb >= 2)
        Check("Server reachable", p.server)
        Check("Microphone allowed", p.micPermission)
        Check(if (p.voiceCommands) "Voice commands: say \"${c.wakePhrase}\"" else "Voice commands unavailable on this phone. Use Talk or type.", p.voiceCommands)
        Text("Wake phrase: \"${c.wakePhrase}, ...\". Short commands: \"partner, stop\", \"partner, pause camera\", \"${c.wakePhrase}, call expert\".", style = MaterialTheme.typography.bodySmall)
        Button(onClick = onGo, enabled = p.micPermission, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("beginVisit")) {
          IconText(Icons.Filled.FiberManualRecord, if (p.videoReady && p.server) "Start recording and go to the door" else "Start anyway")
        }
        OutlinedButton(onClick = { scope.launch { c.checkPreflight() } }, modifier = Modifier.fillMaxWidth()) { IconText(Icons.Outlined.Refresh, "Check again") }
      }
    }
  }
}

@Composable
private fun Check(label: String, ok: Boolean) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
    StatusIcon(if (ok) Status.OK else Status.WARN)
    Spacer(Modifier.width(10.dp))
    Text(label, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun VisitBody(vm: AppViewModel, c: VisitController) {
  val ui by c.ui.collectAsStateWithLifecycle()
  val live by c.live.ui.collectAsStateWithLifecycle()
  val settings by vm.app.settings.values.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  var showConsent by remember { mutableStateOf(false) }
  var showReading by remember { mutableStateOf(false) }
  var showNote by remember { mutableStateOf(false) }
  var confirmEnd by remember { mutableStateOf(false) }
  var typed by remember { mutableStateOf("") }

  LaunchedEffect(ui.phase) {
    if (ui.phase == Phase.ENDED) vm.closeVisit(openReport = true)
    if (ui.phase == Phase.DECLINED && c.ui.value.recording.not()) vm.closeVisit(openReport = false)
  }

  Column(Modifier.fillMaxSize()) {
    Header(c, ui, live)
    Box(Modifier.fillMaxWidth().height(240.dp).background(Color.Black), contentAlignment = Alignment.BottomCenter) {
      val track = live.localVideoTrack
      val room = c.live.roomOrNull
      if (track != null && room != null && !ui.cameraPaused) VideoPreview(room, track)
      else Text(if (ui.cameraPaused) "Camera paused" else "Waiting for video...", color = Color.White, modifier = Modifier.align(Alignment.Center))
      live.caption?.let { cap ->
        Text(cap.text, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(10.dp).background(Color(0xCC111418), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp))
      }
    }
    ui.safetyAlert?.let { alert ->
      Row(Modifier.fillMaxWidth().background(FpColors.Danger).clickable { c.acknowledgeSafety() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Report, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text("STOP", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
          Text(alert, color = Color.White)
        }
        Text("Acknowledge", color = Color.White, fontWeight = FontWeight.Bold)
      }
    }
    if (ui.phase == Phase.PRE_VISIT) {
      Button(onClick = { showConsent = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary), shape = RoundedCornerShape(0.dp), modifier = Modifier.fillMaxWidth().height(56.dp).testTag("consentButton")) {
        IconText(Icons.Outlined.Draw, "Get customer consent")
      }
    }
    Controls(c, ui, onReading = { showReading = true }, onNote = { showNote = true }, onEnd = { confirmEnd = true })
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(typed, { typed = it }, placeholder = { Text("Type a question for your partner") }, singleLine = true, modifier = Modifier.weight(1f).testTag("askField"))
      Spacer(Modifier.width(6.dp))
      Button(onClick = {
        val q = typed
        typed = ""
        scope.launch { c.askTyped(q) }
      }, enabled = typed.isNotBlank(), modifier = Modifier.testTag("askSend")) { IconText(Icons.AutoMirrored.Filled.Send, "Ask") }
    }
    LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
      if (ui.readings.isNotEmpty()) {
        item { SectionTitle("Readings") }
        items(ui.readings) { r -> Text("${r.name}: ${r.value} ${r.unit}") }
      }
      ui.estimateDraft?.let { est ->
        item { SectionTitle("Estimate draft (not shown to customer)") }
        items(est.entries.sortedBy { it.key }.toList()) { (k, v) -> Text("${k.replaceFirstChar { it.uppercase() }}: $v") }
      }
      item { SectionTitle("Activity") }
      items(ui.notices, key = { it.id }) { n -> NoticeRow(n) }
      if (settings.showTranscript) {
        item { SectionTitle("Transcript") }
        if (ui.livePartial.isNotEmpty()) item { Text(ui.livePartial, color = MaterialTheme.colorScheme.outline) }
        items(ui.transcript.takeLast(30).reversed()) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
      }
    }
  }

  if (showConsent) ConsentDialog(vm, c) { showConsent = false }
  if (showReading) ReadingDialog(c) { showReading = false }
  if (showNote) NoteDialog(c) { showNote = false }
  if (confirmEnd) {
    AlertDialog(
      onDismissRequest = { confirmEnd = false },
      title = { Text("End this visit?") },
      text = { Text("Recording stops and the report is drafted from this visit.") },
      confirmButton = {
        TextButton(onClick = {
          confirmEnd = false
          scope.launch { c.end() }
        }, modifier = Modifier.testTag("confirmEnd")) { Text("End visit and build report") }
      },
      dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep working") } },
    )
  }
}

@Composable
private fun SectionTitle(t: String) {
  Text(t, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Header(c: VisitController, ui: VisitController.Ui, live: LiveSession.Ui) {
  val partner =
    when {
      live.state == LiveSession.State.CONNECTING -> "Connecting..."
      live.state == LiveSession.State.RECONNECTING -> "Reconnecting..."
      live.state != LiveSession.State.CONNECTED -> "Partner offline"
      !live.agentPresent -> "Waiting for AI partner"
      live.expertPresent -> "Expert on the line${live.expertName?.let { ": $it" } ?: ""}"
      else ->
        when (live.assistant) {
          "listening" -> "Listening"
          "thinking" -> "Thinking"
          "speaking" -> "Speaking"
          "muted" -> "AI muted (expert live)"
          else -> "Standing by. Say \"${c.wakePhrase}\""
        }
    }
  Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = if (ui.recording) FpColors.Danger else MaterialTheme.colorScheme.outline, modifier = Modifier.size(14.dp))
      Spacer(Modifier.width(4.dp))
      Text(if (ui.recording) "REC" else "Not recording", color = if (ui.recording) FpColors.Danger else MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelLarge)
      Spacer(Modifier.width(12.dp))
      Text(partner, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("partnerStatus"))
      if (ui.pendingHints > 0) {
        Icon(Icons.Outlined.Lightbulb, contentDescription = "Tips waiting", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text("${ui.pendingHints}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
      }
    }
    Text(
      listOfNotNull(
          c.job.title,
          ui.consentChoice?.let { "consent: ${it.replace('_', ' ')}" } ?: "consent: not yet",
          if (ui.escalation != "none") "expert: ${ui.escalation}" else null,
          if (ui.queuedEvents > 0) "${ui.queuedEvents} events queued" else null,
        )
        .joinToString(" · "),
      style = MaterialTheme.typography.labelSmall,
    )
    ui.voiceStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = FpColors.Warning) }
    ui.errorBanner?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
  }
}

@Composable
private fun Controls(c: VisitController, ui: VisitController.Ui, onReading: () -> Unit, onNote: () -> Unit, onEnd: () -> Unit) {
  val scope = rememberCoroutineScope()
  val ending = ui.phase == Phase.ENDING
  Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Button(onClick = { c.talk() }, enabled = !ending, modifier = Modifier.weight(1.6f).height(56.dp).testTag("talk")) { IconText(Icons.Filled.Mic, "Talk") }
      FilledTonalButton(onClick = { c.stopTalking() }, enabled = !ending, modifier = Modifier.weight(1f).height(56.dp)) { IconText(Icons.Filled.Stop, "Stop") }
      FilledTonalButton(onClick = { scope.launch { c.setCameraPaused(!ui.cameraPaused) } }, enabled = !ending, modifier = Modifier.weight(1.3f).height(56.dp)) {
        IconText(if (ui.cameraPaused) Icons.Outlined.Videocam else Icons.Outlined.VideocamOff, if (ui.cameraPaused) "Camera on" else "Camera off")
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      OutlinedButton(onClick = { scope.launch { c.escalate("Technician tapped Call expert") } }, enabled = !ending && ui.escalation == "none", modifier = Modifier.weight(1.5f), contentPadding = PaddingValues(horizontal = 8.dp)) {
        IconText(Icons.Outlined.SupportAgent, if (ui.escalation == "none") "Expert" else ui.escalation.replaceFirstChar { it.uppercase() })
      }
      OutlinedButton(onClick = onReading, enabled = !ending, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { IconText(Icons.Outlined.Speed, "Reading") }
      OutlinedButton(onClick = onNote, enabled = !ending, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { IconText(Icons.Outlined.EditNote, "Note") }
      OutlinedButton(onClick = onEnd, enabled = !ending, modifier = Modifier.weight(0.9f).testTag("endVisit"), contentPadding = PaddingValues(horizontal = 6.dp)) { Text(if (ending) "Ending" else "End") }
    }
  }
}

@Composable
private fun NoticeRow(n: Notice) {
  val (icon, color) =
    when (n.kind) {
      Notice.Kind.SAFETY -> Icons.Filled.Report to FpColors.Danger
      Notice.Kind.HINT -> Icons.Outlined.Lightbulb to MaterialTheme.colorScheme.primary
      Notice.Kind.TOOL -> Icons.Outlined.CheckCircle to FpColors.Success
      Notice.Kind.ESCALATION -> Icons.Outlined.SupportAgent to MaterialTheme.colorScheme.primary
      Notice.Kind.ERROR -> Icons.Outlined.ErrorOutline to FpColors.Warning
      Notice.Kind.INFO -> Icons.Outlined.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
  Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(18.dp))
    Spacer(Modifier.width(10.dp))
    Column {
      Text(n.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
      if (n.detail.isNotBlank()) Text(n.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun VideoPreview(room: io.livekit.android.room.Room, track: LocalVideoTrack) {
  AndroidView(
    factory = { ctx -> TextureViewRenderer(ctx).also { r -> room.initVideoRenderer(r) } },
    modifier = Modifier.fillMaxSize(),
    update = { r ->
      val prev = r.tag as? LocalVideoTrack
      if (prev !== track) {
        prev?.removeRenderer(r)
        track.addRenderer(r)
        r.tag = track
      }
    },
    onRelease = { r ->
      (r.tag as? LocalVideoTrack)?.removeRenderer(r)
      r.release()
    },
  )
}

// ---- Consent ---------------------------------------------------------------------------------

@Composable
private fun ConsentDialog(vm: AppViewModel, c: VisitController, onClose: () -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val ui by c.ui.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  var language by remember { mutableStateOf("en") }
  var choice by remember { mutableStateOf("video_audio") }
  var privateAreas by remember { mutableStateOf(false) }
  var name by remember { mutableStateOf("") }
  var role by remember { mutableStateOf("owner") }
  val strokes = remember { mutableStateListOf<List<Offset>>() }
  var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
  var busy by remember { mutableStateOf(false) }
  var confirmDecline by remember { mutableStateOf(false) }
  val company = state.company?.name ?: "Our company"
  val es = language == "es"

  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Card(Modifier.fillMaxSize().padding(8.dp)) {
      Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row {
          listOf("en" to "English", "es" to "Español").forEach { (code, label) ->
            Row(Modifier.clickable { language = code }, verticalAlignment = Alignment.CenterVertically) {
              RadioButton(language == code, { language = code })
              Text(label)
              Spacer(Modifier.width(12.dp))
            }
          }
        }
        Text(if (es) "Autorización de servicio" else "Service authorization", style = MaterialTheme.typography.titleMedium)
        Text(ConsentText.serviceAuthorization(company, state.company?.diagnostic_fee_cents, language), style = MaterialTheme.typography.bodyLarge)
        Text(if (es) "Grabación" else "Recording", style = MaterialTheme.typography.titleMedium)
        Text(ConsentText.recording(language), style = MaterialTheme.typography.bodyLarge)
        ConsentText.choices.forEach { ch ->
          Row(Modifier.fillMaxWidth().clickable { choice = ch.id }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(choice == ch.id, { choice = ch.id }, modifier = Modifier.testTag("choice_${ch.id}"))
            Text(if (es) ch.es else ch.en, style = MaterialTheme.typography.titleMedium)
          }
        }
        if (choice == "video_audio") {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (es) "OK grabar en baños o dormitorios si el equipo está ahí" else "OK to record in bathrooms or bedrooms if equipment is there", Modifier.weight(1f))
            Switch(privateAreas, { privateAreas = it })
          }
        }
        HorizontalDivider()
        OutlinedTextField(name, { name = it }, label = { Text(if (es) "Nombre completo" else "Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("signerName"))
        Row {
          listOf("owner" to (if (es) "Dueño" else "Owner"), "tenant" to (if (es) "Inquilino" else "Tenant"), "authorized" to (if (es) "Autorizado" else "Authorized")).forEach { (id, label) ->
            Row(Modifier.clickable { role = id }, verticalAlignment = Alignment.CenterVertically) {
              RadioButton(role == id, { role = id })
              Text(label)
            }
          }
        }
        Text(if (es) "Firme aquí" else "Sign here", style = MaterialTheme.typography.labelLarge)
        ComposeCanvas(
          Modifier.fillMaxWidth().height(160.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).background(Color.White).testTag("signaturePad").pointerInput(Unit) {
            detectDragGestures(
              onDragStart = { p -> current = listOf(p) },
              onDrag = { change, _ -> current = current + change.position },
              onDragEnd = {
                if (current.size > 1) strokes.add(current)
                current = emptyList()
              },
            )
          }
        ) {
          (strokes + listOf(current)).forEach { line ->
            if (line.size > 1) {
              val path = Path().apply {
                moveTo(line[0].x, line[0].y)
                line.drop(1).forEach { lineTo(it.x, it.y) }
              }
              drawPath(path, Color.Black, style = Stroke(width = 5f))
            }
          }
        }
        TextButton(onClick = { strokes.clear() }) { Text(if (es) "Borrar firma" else "Clear signature") }
        Button(
          onClick = {
            busy = true
            scope.launch {
              val ok = c.submitConsent(choice, name, role, privateAreas, renderSignature(strokes), language)
              busy = false
              if (ok) onClose()
            }
          },
          enabled = !busy && name.isNotBlank() && strokes.isNotEmpty(),
          modifier = Modifier.fillMaxWidth().height(56.dp).testTag("signAgree"),
        ) { if (busy) CircularProgressIndicator(Modifier.width(22.dp)) else Text(if (es) "Firmar y aceptar" else "Sign and agree", fontWeight = FontWeight.Bold) }
        TextButton(onClick = { confirmDecline = true }) { Text(if (es) "El cliente no acepta el servicio" else "Customer declines service", color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onClose) { Text("Close") }
        ui.errorBanner?.let { ErrorText(it) }
      }
    }
  }
  if (confirmDecline) {
    AlertDialog(
      onDismissRequest = { confirmDecline = false },
      title = { Text("Customer declines service?") },
      text = { Text("Don't enter. Tell them they can call the office any time.") },
      confirmButton = {
        TextButton(onClick = {
          confirmDecline = false
          scope.launch {
            c.submitConsent("declined_service", name, role, false, null, language)
            onClose()
          }
        }) { Text("Yes, end the visit") }
      },
      dismissButton = { TextButton(onClick = { confirmDecline = false }) { Text("Back") } },
    )
  }
}

/** Draws the strokes into a 600x200 white PNG-ready bitmap, scaled to fit. */
private fun renderSignature(strokes: List<List<Offset>>): Bitmap? {
  if (strokes.isEmpty()) return null
  val pts = strokes.flatten()
  val maxX = pts.maxOf { it.x }.coerceAtLeast(1f)
  val maxY = pts.maxOf { it.y }.coerceAtLeast(1f)
  val scale = minOf(600f / maxX, 200f / maxY, 2f)
  val bmp = Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bmp)
  canvas.drawColor(android.graphics.Color.WHITE)
  val paint = Paint().apply {
    color = android.graphics.Color.BLACK
    strokeWidth = 4f
    style = Paint.Style.STROKE
    isAntiAlias = true
  }
  for (line in strokes) {
    for (i in 1 until line.size) canvas.drawLine(line[i - 1].x * scale, line[i - 1].y * scale, line[i].x * scale, line[i].y * scale, paint)
  }
  return bmp
}

// ---- Readings and notes ----------------------------------------------------------------------

@Composable
private fun ReadingDialog(c: VisitController, onClose: () -> Unit) {
  var name by remember { mutableStateOf("") }
  var value by remember { mutableStateOf("") }
  var unit by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onClose,
    title = { Text("Add a reading") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("What, e.g. CO ambient") }, singleLine = true)
        OutlinedTextField(value, { value = it }, label = { Text("Value") }, singleLine = true)
        OutlinedTextField(unit, { unit = it }, label = { Text("Unit, e.g. ppm, V, psi") }, singleLine = true)
      }
    },
    confirmButton = {
      TextButton(onClick = {
        c.addReading(name.trim(), value.trim(), unit.trim())
        onClose()
      }, enabled = name.isNotBlank() && value.isNotBlank()) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
  )
}

@Composable
private fun NoteDialog(c: VisitController, onClose: () -> Unit) {
  var text by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onClose,
    title = { Text("Add a note") },
    text = { OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
    confirmButton = {
      TextButton(onClick = {
        c.addNote(text)
        onClose()
      }, enabled = text.isNotBlank()) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
  )
}
