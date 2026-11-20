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
    PageColumn(mod) {
      SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconBadge(tradeIcon(c.job.trade), size = 48.dp)
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Text(c.job.title, style = MaterialTheme.typography.titleLarge, color = Fp.palette.ink)
            Text("Quick check before recording starts", style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted)
          }
        }
      }
      val p = ui.preflight
      if (p == null) {
        Loading()
      } else {
        SectionCard {
          Check("Video: ${p.videoDetail}", p.videoReady)
          p.glassesBattery?.let { Check("Glasses battery $it%", it >= 20) }
          Check("Phone battery ${p.phoneBattery}%", p.phoneBattery >= 20)
          Check("Free storage ${"%.1f".format(p.freeStorageGb)} GB", p.freeStorageGb >= 2)
          Check("Server reachable", p.server)
          Check("Microphone allowed", p.micPermission)
          Check(if (p.voiceCommands) "Voice commands: say \"${c.wakePhrase}\"" else "Voice commands unavailable on this phone. Use Talk or type.", p.voiceCommands)
        }
        Banner("Say \"${c.wakePhrase}, ...\" to ask. \"Partner, stop\" stops it. \"${c.wakePhrase}, call expert\" pages your expert.", BannerKind.INFO)
        PrimaryButton(if (p.videoReady && p.server) "Start recording" else "Start anyway", onClick = onGo, enabled = p.micPermission, icon = Icons.Filled.FiberManualRecord, tall = true, modifier = Modifier.testTag("beginVisit"))
        SecondaryButton("Check again", onClick = { scope.launch { c.checkPreflight() } }, icon = Icons.Outlined.Refresh)
      }
    }
  }
}

@Composable
private fun Check(label: String, ok: Boolean) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    StatusIcon(if (ok) Status.OK else Status.WARN, Modifier.size(22.dp))
    Spacer(Modifier.width(12.dp))
    Text(label, style = MaterialTheme.typography.bodyMedium, color = Fp.palette.ink)
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

  Column(Modifier.fillMaxSize().background(Fp.palette.canvas)) {
    Header(c, ui, live)
    Box(Modifier.fillMaxWidth().height(240.dp).background(Color.Black), contentAlignment = Alignment.BottomCenter) {
      val track = live.localVideoTrack
      val room = c.live.roomOrNull
      if (track != null && room != null && !ui.cameraPaused) VideoPreview(room, track)
      else Text(if (ui.cameraPaused) "Camera paused" else "Waiting for video...", color = Color.White, modifier = Modifier.align(Alignment.Center))
      live.caption?.let { cap ->
        Text(cap.text, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(10.dp).background(Color(0xCC18191A), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp))
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
      Box(Modifier.fillMaxWidth().background(Fp.palette.card).padding(horizontal = 12.dp, vertical = 10.dp)) {
        PrimaryButton("Get customer consent", onClick = { showConsent = true }, icon = Icons.Outlined.Draw, color = FpColors.Warning, tall = true, modifier = Modifier.testTag("consentButton"))
      }
    }
    Controls(c, ui, onReading = { showReading = true }, onNote = { showNote = true }, onEnd = { confirmEnd = true })
    Row(Modifier.fillMaxWidth().background(Fp.palette.card).padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.weight(1f).height(44.dp).background(Fp.palette.canvas, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        androidx.compose.foundation.text.BasicTextField(
          typed,
          { typed = it },
          singleLine = true,
          textStyle = MaterialTheme.typography.bodyMedium.copy(color = Fp.palette.ink),
          cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
          modifier = Modifier.fillMaxWidth().testTag("askField"),
          decorationBox = { inner ->
            if (typed.isEmpty()) Text("Ask your partner a question", style = MaterialTheme.typography.bodyMedium, color = Fp.palette.faint)
            inner()
          },
        )
      }
      Spacer(Modifier.width(8.dp))
      Box(
        Modifier.size(44.dp).background(if (typed.isNotBlank()) MaterialTheme.colorScheme.primary else Fp.palette.chip, androidx.compose.foundation.shape.CircleShape).clickable(enabled = typed.isNotBlank()) {
          val q = typed
          typed = ""
          scope.launch { c.askTyped(q) }
        }.testTag("askSend"),
        contentAlignment = Alignment.Center,
      ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask", tint = if (typed.isNotBlank()) Color.White else Fp.palette.faint, modifier = Modifier.size(20.dp)) }
    }
    HorizontalDivider(color = Fp.palette.line)
    LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
      if (ui.readings.isNotEmpty()) {
        item { SectionTitle("Readings") }
        items(ui.readings) { r -> Text("${r.name}: ${r.value} ${r.unit}", style = MaterialTheme.typography.bodyMedium, color = Fp.palette.ink, modifier = Modifier.padding(vertical = 2.dp)) }
      }
      ui.estimateDraft?.let { est ->
        item { SectionTitle("Estimate draft (not shown to customer)") }
        items(est.entries.sortedBy { it.key }.toList()) { (k, v) -> Text("${k.replaceFirstChar { it.uppercase() }}: $v", style = MaterialTheme.typography.bodyMedium, color = Fp.palette.ink, modifier = Modifier.padding(vertical = 2.dp)) }
      }
      item { SectionTitle("Activity") }
      items(ui.notices, key = { it.id }) { n -> NoticeRow(n) }
      if (settings.showTranscript) {
        item { SectionTitle("Transcript") }
        if (ui.livePartial.isNotEmpty()) item { Text(ui.livePartial, style = MaterialTheme.typography.bodySmall, color = Fp.palette.faint) }
        items(ui.transcript.takeLast(30).reversed()) { line -> Text(line, style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted, modifier = Modifier.padding(vertical = 2.dp)) }
      }
    }
  }

  if (showConsent) ConsentDialog(vm, c) { showConsent = false }
  if (showReading) ReadingDialog(c) { showReading = false }
  if (showNote) NoteDialog(c) { showNote = false }
  if (confirmEnd) {
    AlertDialog(
      onDismissRequest = { confirmEnd = false },
      containerColor = Fp.palette.card,
      title = { Text("End this visit?", style = MaterialTheme.typography.titleLarge) },
      text = { Text("Recording stops and the AI drafts the report from this visit.", style = MaterialTheme.typography.bodyMedium, color = Fp.palette.muted) },
      confirmButton = {
        LinkButton("End visit", onClick = {
          confirmEnd = false
          scope.launch { c.end() }
        }, modifier = Modifier.testTag("confirmEnd"), color = MaterialTheme.colorScheme.error)
      },
      dismissButton = { LinkButton("Keep working", onClick = { confirmEnd = false }) },
    )
  }
}

@Composable
private fun SectionTitle(t: String) {
  Text(t, style = MaterialTheme.typography.titleSmall, color = Fp.palette.muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
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
  Column(Modifier.fillMaxWidth().background(Fp.palette.card).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Row(
        Modifier.background(if (ui.recording) Fp.palette.dangerTint else Fp.palette.chip, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = if (ui.recording) FpColors.Danger else Fp.palette.faint, modifier = Modifier.size(10.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (ui.recording) "REC" else "Not recording", color = if (ui.recording) FpColors.Danger else Fp.palette.muted, style = MaterialTheme.typography.labelMedium)
      }
      Spacer(Modifier.width(10.dp))
      Text(partner, style = MaterialTheme.typography.titleSmall, color = Fp.palette.ink, modifier = Modifier.weight(1f).testTag("partnerStatus"))
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
      style = MaterialTheme.typography.bodySmall,
      color = Fp.palette.muted,
    )
    ui.voiceStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = FpColors.Warning) }
    ui.errorBanner?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
  }
}

@Composable
private fun Controls(c: VisitController, ui: VisitController.Ui, onReading: () -> Unit, onNote: () -> Unit, onEnd: () -> Unit) {
  val scope = rememberCoroutineScope()
  val ending = ui.phase == Phase.ENDING
  Column(Modifier.fillMaxWidth().background(Fp.palette.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      PrimaryButton("Talk", onClick = { c.talk() }, enabled = !ending, icon = Icons.Filled.Mic, tall = true, modifier = Modifier.weight(1.2f).testTag("talk"))
      SecondaryButton("Stop", onClick = { c.stopTalking() }, enabled = !ending, icon = Icons.Filled.Stop, fill = false, modifier = Modifier.weight(1f).height(52.dp))
      SecondaryButton(if (ui.cameraPaused) "Camera on" else "Camera off", onClick = { scope.launch { c.setCameraPaused(!ui.cameraPaused) } }, enabled = !ending, icon = if (ui.cameraPaused) Icons.Outlined.Videocam else Icons.Outlined.VideocamOff, fill = false, modifier = Modifier.weight(1.5f).height(52.dp))
    }
    Row {
      ToolAction(Icons.Outlined.SupportAgent, if (ui.escalation == "none") "Expert" else ui.escalation.replaceFirstChar { it.uppercase() }, enabled = !ending && ui.escalation == "none") { scope.launch { c.escalate("Technician tapped Call expert") } }
      ToolAction(Icons.Outlined.Speed, "Reading", enabled = !ending, onClick = onReading)
      ToolAction(Icons.Outlined.EditNote, "Note", enabled = !ending, onClick = onNote)
      ToolAction(Icons.Filled.Stop, if (ending) "Ending" else "End", enabled = !ending, danger = true, modifier = Modifier.testTag("endVisit"), onClick = onEnd)
    }
  }
  HorizontalDivider(color = Fp.palette.line)
}

/** Icon over a short label, four across, like a toolbar under a post. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ToolAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
  val tint = when {
    !enabled -> Fp.palette.faint
    danger -> MaterialTheme.colorScheme.error
    else -> Fp.palette.ink
  }
  Column(modifier.weight(1f).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(40.dp).background(if (danger && enabled) Fp.palette.dangerTint else Fp.palette.chip, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
    Spacer(Modifier.height(4.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
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
      Notice.Kind.INFO -> Icons.Outlined.Info to Fp.palette.muted
    }
  Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(18.dp))
    Spacer(Modifier.width(10.dp))
    Column {
      Text(n.title, style = MaterialTheme.typography.titleSmall, color = Fp.palette.ink)
      if (n.detail.isNotBlank()) Text(n.detail, style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted)
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
    Column(Modifier.fillMaxSize().background(Fp.palette.canvas)) {
      Row(Modifier.fillMaxWidth().background(Fp.palette.card).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LinkButton(if (es) "Cerrar" else "Close", onClick = onClose)
        Text(if (es) "Consentimiento del cliente" else "Customer consent", style = MaterialTheme.typography.titleMedium, color = Fp.palette.ink, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.width(72.dp))
      }
      HorizontalDivider(color = Fp.palette.line)
      Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Segmented(listOf("en" to "English", "es" to "Español"), language, { language = it })
        SectionCard(title = if (es) "Autorización de servicio" else "Service authorization") {
          Text(ConsentText.serviceAuthorization(company, state.company?.diagnostic_fee_cents, language), style = MaterialTheme.typography.bodyLarge, color = Fp.palette.ink)
        }
        SectionCard(title = if (es) "Grabación" else "Recording") {
          Text(ConsentText.recording(language), style = MaterialTheme.typography.bodyLarge, color = Fp.palette.ink)
        }
        ConsentText.choices.forEach { ch ->
          ChoiceCard(if (es) ch.es else ch.en, "", choice == ch.id, { choice = ch.id }, modifier = Modifier.testTag("choice_${ch.id}"))
        }
        if (choice == "video_audio") {
          SectionCard(padding = PaddingValues(vertical = 4.dp)) {
            SwitchRow(if (es) "OK grabar en baños o dormitorios si el equipo está ahí" else "OK to record in bathrooms or bedrooms if equipment is there", privateAreas) { privateAreas = it }
          }
        }
        SectionCard(title = if (es) "Firma" else "Signature") {
        FpTextField(name, { name = it }, if (es) "Nombre completo" else "Full name", modifier = Modifier.testTag("signerName"))
        Segmented(listOf("owner" to (if (es) "Dueño" else "Owner"), "tenant" to (if (es) "Inquilino" else "Tenant"), "authorized" to (if (es) "Autorizado" else "Authorized")), role, { role = it })
        Text(if (es) "Firme aquí" else "Sign here", style = MaterialTheme.typography.labelMedium, color = Fp.palette.muted)
        ComposeCanvas(
          Modifier.fillMaxWidth().height(170.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).background(Color.White, RoundedCornerShape(8.dp)).testTag("signaturePad").pointerInput(Unit) {
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
        LinkButton(if (es) "Borrar firma" else "Clear signature", onClick = { strokes.clear() }, color = Fp.palette.muted)
        }
        PrimaryButton(
          if (es) "Firmar y aceptar" else "Sign and agree",
          onClick = {
            busy = true
            scope.launch {
              val ok = c.submitConsent(choice, name, role, privateAreas, renderSignature(strokes), language)
              busy = false
              if (ok) onClose()
            }
          },
          enabled = name.isNotBlank() && strokes.isNotEmpty(),
          loading = busy,
          tall = true,
          modifier = Modifier.testTag("signAgree"),
        )
        LinkButton(if (es) "El cliente no acepta el servicio" else "Customer declines service", onClick = { confirmDecline = true }, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.CenterHorizontally))
        ui.errorBanner?.let { ErrorText(it) }
        Spacer(Modifier.height(24.dp))
      }
    }
  }
  if (confirmDecline) {
    AlertDialog(
      onDismissRequest = { confirmDecline = false },
      containerColor = Fp.palette.card,
      title = { Text("Customer declines service?", style = MaterialTheme.typography.titleLarge) },
      text = { Text("Don't enter. Tell them they can call the office any time.", style = MaterialTheme.typography.bodyMedium, color = Fp.palette.muted) },
      confirmButton = {
        LinkButton("Yes, end the visit", onClick = {
          confirmDecline = false
          scope.launch {
            c.submitConsent("declined_service", name, role, false, null, language)
            onClose()
          }
        }, color = MaterialTheme.colorScheme.error)
      },
      dismissButton = { LinkButton("Back", onClick = { confirmDecline = false }) },
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
    containerColor = Fp.palette.card,
    title = { Text("Add a reading", style = MaterialTheme.typography.titleLarge) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FpTextField(name, { name = it }, "What, e.g. CO ambient")
        FpTextField(value, { value = it }, "Value")
        FpTextField(unit, { unit = it }, "Unit, e.g. ppm, V, psi")
      }
    },
    confirmButton = {
      TextButton(onClick = {
        c.addReading(name.trim(), value.trim(), unit.trim())
        onClose()
      }, enabled = name.isNotBlank() && value.isNotBlank()) { Text("Save", style = MaterialTheme.typography.labelLarge) }
    },
    dismissButton = { LinkButton("Cancel", onClick = onClose, color = Fp.palette.muted) },
  )
}

@Composable
private fun NoteDialog(c: VisitController, onClose: () -> Unit) {
  var text by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onClose,
    containerColor = Fp.palette.card,
    title = { Text("Add a note", style = MaterialTheme.typography.titleLarge) },
    text = { FpTextField(text, { text = it }, "Note", singleLine = false, minLines = 3) },
    confirmButton = {
      TextButton(onClick = {
        c.addNote(text)
        onClose()
      }, enabled = text.isNotBlank()) { Text("Save", style = MaterialTheme.typography.labelLarge) }
    },
    dismissButton = { LinkButton("Cancel", onClick = onClose, color = Fp.palette.muted) },
  )
}
