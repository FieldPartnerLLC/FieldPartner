package com.fieldpartner.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fieldpartner.app.AppSettings
import com.fieldpartner.app.BuildConfig
import com.fieldpartner.app.MainActivity
import com.fieldpartner.app.VideoSource
import com.fieldpartner.app.core.InviteInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Meta AI app (it manages the glasses). Package name on Google Play. */
const val META_AI_PACKAGE = "com.facebook.stella"

// ---- Join with an invite code ------------------------------------------------------------------

@Composable
fun JoinScreen(vm: AppViewModel) {
  val state by vm.state.collectAsStateWithLifecycle()
  var server by remember { mutableStateOf(vm.app.settings.current.apiBaseUrl) }
  var code by remember { mutableStateOf("") }
  var info by remember { mutableStateOf<InviteInfo?>(null) }
  var pw by remember { mutableStateOf("") }
  var pw2 by remember { mutableStateOf("") }
  FpScaffold("Join your company", onBack = { vm.back() }) { mod ->
    Column(mod.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Your manager sent you an 8-letter invite code by text, WhatsApp or email.", style = MaterialTheme.typography.bodyLarge)
      OutlinedTextField(server, { server = it }, label = { Text("Server address (from the invite message)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("joinServer"))
      OutlinedTextField(
        code,
        { code = it.uppercase().take(12); info = null },
        label = { Text("Invite code, e.g. ABCD-2345") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth().testTag("inviteCode"),
      )
      if (info == null) {
        Button(onClick = { vm.checkInvite(code, server) { info = it } }, enabled = !state.busy && code.count { it.isLetterOrDigit() } >= 8, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("checkCode")) {
          if (state.busy) CircularProgressIndicator(Modifier.width(22.dp)) else Text("Check code")
        }
      }
      info?.let { i ->
        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(14.dp)) {
            Text(i.companyName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text("invited ${i.name} (${i.email}) as ${if (i.role == "tech") "a technician" else "an ${i.role}"}.")
            Text("Not you? Ask your manager for your own code.", style = MaterialTheme.typography.bodySmall)
          }
        }
        OutlinedTextField(pw, { pw = it }, label = { Text("Choose a password (10+ characters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("newPassword"))
        OutlinedTextField(pw2, { pw2 = it }, label = { Text("Password again") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("newPassword2"))
        if (pw2.isNotEmpty() && pw != pw2) ErrorText("The two passwords don't match.")
        Button(onClick = { vm.acceptInvite(code, pw) }, enabled = !state.busy && pw.length >= 10 && pw == pw2, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("createAccount")) {
          if (state.busy) CircularProgressIndicator(Modifier.width(22.dp)) else Text("Create my account")
        }
        Text("Sign in next time with ${i.email} and this password.", style = MaterialTheme.typography.bodySmall)
      }
      ErrorText(state.error)
    }
  }
}

// ---- First-run setup: permissions, camera choice, glasses connection, live video test -------------

private enum class Step { PERMISSIONS, CAMERA, CONNECT, TEST, READY }

@Composable
fun SetupScreen(vm: AppViewModel, activity: MainActivity) {
  val settings by vm.app.settings.values.collectAsStateWithLifecycle()
  var step by remember { mutableStateOf(Step.PERMISSIONS) }
  val title =
    when (step) {
      Step.PERMISSIONS -> "Setup 1 of 4: Allow access"
      Step.CAMERA -> "Setup 2 of 4: Your camera"
      Step.CONNECT -> "Setup 3 of 4: Connect your glasses"
      Step.TEST -> "Setup 4 of 4: Test the video"
      Step.READY -> "You're ready"
    }
  FpScaffold(title) { mod ->
    Column(mod.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      when (step) {
        Step.PERMISSIONS -> PermissionsStep(vm, activity) { step = Step.CAMERA }
        Step.CAMERA -> CameraStep(vm, activity) { src -> step = if (src == VideoSource.GLASSES) Step.CONNECT else Step.TEST }
        Step.CONNECT -> ConnectStep(vm, activity) { step = Step.TEST }
        Step.TEST -> TestStep(vm, activity, settings.videoSource) { step = Step.READY }
        Step.READY -> ReadyStep(vm)
      }
      HorizontalDivider()
      TextButton(onClick = { vm.finishSetup() }, modifier = Modifier.testTag("skipSetup")) { Text("Skip setup for now (you can run it again in Settings)") }
    }
  }
}

/** Step marker: a small lettered badge until done, then a green check. */
@Composable
private fun StepBadge(mark: String?, ok: Boolean) {
  if (ok || mark == null) {
    StatusIcon(if (ok) Status.OK else Status.PENDING, Modifier.padding(top = 1.dp).size(22.dp))
  } else {
    Box(Modifier.padding(top = 1.dp).size(22.dp).background(MaterialTheme.colorScheme.secondaryContainer, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
      Text(mark, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
  }
}

@Composable
private fun StatusLine(label: String, ok: Boolean, detail: String? = null, mark: String? = null) {
  Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
    StepBadge(mark, ok)
    Spacer(Modifier.width(12.dp))
    Column {
      Text(label, fontWeight = if (ok) FontWeight.Normal else FontWeight.SemiBold)
      detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
  }
}

@Composable
private fun PermissionsStep(vm: AppViewModel, activity: MainActivity, onNext: () -> Unit) {
  var tick by remember { mutableIntStateOf(0) }
  fun has(p: String) = activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
  data class Perm(val first: String, val label: String, val why: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
  val perms =
    buildList {
      add(Perm(Manifest.permission.RECORD_AUDIO, "Microphone", "Required. The AI and your expert hear you, and the visit is recorded.", Icons.Outlined.Mic))
      add(Perm(Manifest.permission.BLUETOOTH_CONNECT, "Nearby devices", "Required for Meta glasses (Bluetooth).", Icons.Outlined.Bluetooth))
      add(Perm(Manifest.permission.ACCESS_FINE_LOCATION, "Location", "Stamps where the customer signed consent.", Icons.Outlined.LocationOn))
      if (android.os.Build.VERSION.SDK_INT >= 33) add(Perm(Manifest.permission.POST_NOTIFICATIONS, "Notifications", "Shows that a visit is recording while the phone is in your pocket.", Icons.Outlined.Notifications))
      add(Perm(Manifest.permission.CAMERA, "Camera", "Only used if you work without glasses.", Icons.Outlined.PhotoCamera))
    }
  @Suppress("UNUSED_VARIABLE") val recheck = tick // re-read permissions after each request
  Text("FieldPartner needs these. Tap Allow, then accept each prompt.", style = MaterialTheme.typography.bodyLarge)
  perms.forEach { pm ->
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
      Icon(pm.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 1.dp).size(22.dp))
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(pm.label, style = MaterialTheme.typography.titleSmall)
        Text(pm.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Spacer(Modifier.width(8.dp))
      if (has(pm.first)) StatusIcon(Status.OK) else Text("Not yet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
  Button(
    onClick = {
      activity.requestPermissions((perms.map { it.first } + Manifest.permission.ACCESS_COARSE_LOCATION).toTypedArray()) {
        if (has(Manifest.permission.BLUETOOTH_CONNECT)) runCatching { vm.app.glasses.initialize() }
        tick++
      }
    },
    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("allowPermissions"),
  ) { IconText(Icons.Outlined.Security, "Allow") }
  if (!has(Manifest.permission.RECORD_AUDIO) && tick > 0) {
    ErrorText("The microphone is required. If Android stopped asking, open Settings > Apps > FieldPartner > Permissions.")
    OutlinedButton(onClick = { activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))) }) { Text("Open app permissions") }
  }
  Button(onClick = onNext, enabled = has(Manifest.permission.RECORD_AUDIO), modifier = Modifier.fillMaxWidth().testTag("permissionsNext")) { Text("Next") }
}

@Composable
private fun CameraStep(vm: AppViewModel, activity: MainActivity, onNext: (VideoSource) -> Unit) {
  val s by vm.app.settings.values.collectAsStateWithLifecycle()
  val showSimulated = AppSettings.isEmulator() || BuildConfig.DEBUG
  Text("What will you wear on the job?", style = MaterialTheme.typography.bodyLarge)
  val options = buildList {
    add(Triple(VideoSource.GLASSES, "Meta glasses", "Ray-Ban Meta or Oakley Meta. Hands free: the AI sees what you see."))
    add(Triple(VideoSource.PHONE_CAMERA, "Phone camera", "No glasses yet. Uses the phone's back camera. Good for trying the app."))
    if (showSimulated) add(Triple(VideoSource.SIMULATED_GLASSES, "Simulated glasses", "Plays a demo video through Meta's glasses simulator. For testing and the emulator."))
  }
  options.forEach { (src, title, text) ->
    Row(Modifier.fillMaxWidth().clickable { vm.app.settings.update { it.copy(videoSource = src) } }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      RadioButton(selected = s.videoSource == src, onClick = { vm.app.settings.update { it.copy(videoSource = src) } }, modifier = Modifier.testTag("src_${src.name}"))
      Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
  Button(
    onClick = {
      if (s.videoSource != VideoSource.SIMULATED_GLASSES && vm.app.glasses.status.value.simulated) vm.app.glasses.stopSimulated()
      if (s.videoSource == VideoSource.PHONE_CAMERA && activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        activity.requestPermissions(arrayOf(Manifest.permission.CAMERA)) { onNext(s.videoSource) }
      } else {
        onNext(s.videoSource)
      }
    },
    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("cameraNext"),
  ) { Text("Next") }
}

@Composable
private fun ConnectStep(vm: AppViewModel, activity: MainActivity, onNext: () -> Unit) {
  val gs by vm.app.glasses.status.collectAsStateWithLifecycle()
  val ctx = LocalContext.current
  var cameraOk by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }
  val metaInstalled = runCatching { ctx.packageManager.getPackageInfo(META_AI_PACKAGE, 0) }.isSuccess
  LaunchedEffect(Unit) { runCatching { vm.app.glasses.initialize() } }
  LaunchedEffect(gs.isRegistered) {
    // Re-check after returning from the Meta AI app.
    while (true) {
      cameraOk = gs.isRegistered && runCatching { vm.app.glasses.cameraPermissionGranted() }.getOrDefault(false)
      delay(2_000)
    }
  }
  activity.glassesPermissionMessage = { message = it }

  Text("Do these in order. Each line turns into a green tick when it's done.", style = MaterialTheme.typography.bodyLarge)

  StatusLine(mark = "A", label = "Meta AI app installed and your glasses paired in it", ok = metaInstalled, detail = "Open Meta AI, sign in with your Meta account, tap the glasses icon and follow its pairing steps. Keep the glasses charged.")
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(onClick = {
      val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$META_AI_PACKAGE"))
      runCatching { ctx.startActivity(market) }.onFailure { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$META_AI_PACKAGE"))) }
    }) { Text(if (metaInstalled) "Update Meta AI" else "Get Meta AI") }
    if (metaInstalled) OutlinedButton(onClick = { ctx.packageManager.getLaunchIntentForPackage(META_AI_PACKAGE)?.let { ctx.startActivity(it) } }) { Text("Open Meta AI") }
  }

  StatusLine(
    mark = "B",
    label = "Developer Mode on in Meta AI",
    ok = gs.isRegistered,
    detail = "In Meta AI: Settings (gear) > App info > tap the version number 5 times > turn on Developer Mode. Needed until your company registers FieldPartner with Meta.",
  )

  StatusLine(mark = "C", label = "FieldPartner connected to your glasses", ok = gs.isRegistered, detail = "Tap Connect. Meta AI opens and asks to allow FieldPartner. Tap Allow, then come back here.")
  Button(onClick = { vm.app.glasses.clearError(); vm.app.glasses.register(activity) }, modifier = Modifier.fillMaxWidth().testTag("connectGlasses")) {
    IconText(Icons.Outlined.Link, if (gs.isRegistered) "Connected. Connect again" else "Connect")
  }

  StatusLine(mark = "D", label = "Glasses camera allowed", ok = cameraOk, detail = "Tap Allow camera. Meta AI asks once. Choose Allow always.")
  OutlinedButton(onClick = { activity.requestGlassesCamera() }, enabled = gs.isRegistered, modifier = Modifier.fillMaxWidth()) { IconText(Icons.Outlined.PhotoCamera, "Allow camera") }

  StatusLine(
    mark = "E",
    label = "Glasses found",
    ok = gs.deviceCount > 0,
    detail = if (gs.deviceCount > 0) "${gs.deviceName ?: "Glasses"}${gs.battery?.let { ", battery $it%" } ?: ""}" else "Take the glasses out of the case, unfold them and put them on. Bluetooth must be on.",
  )

  message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
  gs.lastError?.let { ErrorText(it) }
  Button(onClick = onNext, enabled = gs.isRegistered && cameraOk, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("connectNext")) { Text("Next: test the video") }
}

@Composable
private fun TestStep(vm: AppViewModel, activity: MainActivity, source: VideoSource, onNext: () -> Unit) {
  val gs by vm.app.glasses.status.collectAsStateWithLifecycle()
  val preview by vm.app.glasses.preview.collectAsStateWithLifecycle()
  var running by remember { mutableStateOf(false) }
  var startFrames by remember { mutableStateOf(0L) }
  val scope = rememberCoroutineScope()
  DisposableEffect(Unit) {
    onDispose {
      vm.app.glasses.previewEnabled = false
      vm.app.glasses.stopStreaming()
    }
  }
  if (source == VideoSource.PHONE_CAMERA) {
    Text("You chose the phone camera. It turns on when you start a visit: hold the phone or put it in a chest mount with the back camera facing the work.", style = MaterialTheme.typography.bodyLarge)
    StatusLine("Camera permission", activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("testNext")) { Text("Next") }
    return
  }
  val received = (gs.frames - startFrames).coerceAtLeast(0)
  val ok = running && received >= 15
  Text(
    if (source == VideoSource.SIMULATED_GLASSES) "Plays the demo video through Meta's glasses simulator, the same path real glasses use."
    else "Put the glasses on and look at something. You should see it below within 10 seconds.",
    style = MaterialTheme.typography.bodyLarge,
  )
  Box(Modifier.fillMaxWidth().height(280.dp).background(Color.Black), contentAlignment = Alignment.Center) {
    val bmp = preview
    if (running && bmp != null) Image(bmp.asImageBitmap(), "Live view from the glasses", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(280.dp))
    else Text(if (running) "Waiting for video..." else "Tap Start test", color = Color.White)
  }
  StatusLine("Video received", ok, if (running) "$received frames, stream ${gs.streamState.lowercase()}" else null)
  gs.lastError?.let { ErrorText(it) }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(
      onClick = {
        scope.launch {
          if (source == VideoSource.SIMULATED_GLASSES) {
            val f = java.io.File(activity.cacheDir, "demo-glasses.mp4")
            if (!f.exists() || f.length() == 0L) activity.assets.open("demo-glasses.mp4").use { i -> f.outputStream().use { i.copyTo(it) } }
            val custom = vm.app.settings.current.simulatedVideoUri
            vm.app.glasses.useSimulated(if (custom.isNotBlank()) Uri.parse(custom) else Uri.fromFile(f))
          }
          vm.app.glasses.clearError()
          startFrames = vm.app.glasses.status.value.frames
          vm.app.glasses.previewEnabled = true
          vm.app.glasses.startStreaming()
          running = true
        }
      },
      enabled = !running,
      modifier = Modifier.testTag("startTest"),
    ) { IconText(Icons.Filled.PlayArrow, "Start test") }
    OutlinedButton(onClick = {
      vm.app.glasses.previewEnabled = false
      vm.app.glasses.stopStreaming()
      running = false
    }, enabled = running) { IconText(Icons.Filled.Stop, "Stop") }
  }
  if (running && !ok) {
    Text("No video after 20 seconds? Check: glasses unfolded and worn, Bluetooth on, step D allowed, and the phone and glasses close together. Then Stop and Start again.", style = MaterialTheme.typography.bodySmall)
  }
  Button(onClick = onNext, enabled = ok, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("testNext")) { Text("Next") }
}

@Composable
private fun ReadyStep(vm: AppViewModel) {
  val state by vm.state.collectAsStateWithLifecycle()
  val wake = vm.app.settings.current.wakePhraseOverride.ifBlank { state.company?.wake_phrase ?: "hey dispatch" }
  Text("How to work with your AI partner", style = MaterialTheme.typography.titleMedium)
  val tips =
    listOf(
      Icons.Outlined.PlayCircle to "Open a job and tap Start visit. Recording starts at the door.",
      Icons.Outlined.Draw to "Get the customer's consent on the phone before you go in.",
      Icons.Outlined.RecordVoiceOver to "The AI listens only when you call it. Say \"$wake, what does this error code mean?\"",
      Icons.Outlined.Mic to "Or tap Talk, press your Bluetooth clip button, or type a question.",
      Icons.Outlined.PanTool to "\"Partner, stop\" stops it.",
      Icons.Outlined.SupportAgent to "\"$wake, call expert\" pages your expert, who can see and talk to you.",
      Icons.Outlined.VideocamOff to "\"$wake, pause camera\" before you enter a bathroom or bedroom.",
      Icons.Outlined.Description to "Tap End when you leave. Check the report, then Approve and send.",
    )
  tips.forEach { (icon, text) ->
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
      Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(20.dp))
      Spacer(Modifier.width(12.dp))
      Text(text, style = MaterialTheme.typography.bodyLarge)
    }
  }
  Spacer(Modifier.height(8.dp))
  Button(onClick = { vm.finishSetup() }, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("finishSetup")) { IconText(Icons.AutoMirrored.Filled.ArrowForward, "Go to my jobs") }
}
