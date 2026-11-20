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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
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
  var showServer by remember { mutableStateOf(!vm.app.settings.current.apiBaseUrl.startsWith("https://")) }
  var code by remember { mutableStateOf("") }
  var info by remember { mutableStateOf<InviteInfo?>(null) }
  var pw by remember { mutableStateOf("") }
  var pw2 by remember { mutableStateOf("") }
  FpScaffold("Join your company", onBack = { vm.back() }) { mod ->
    PageColumn(mod) {
      SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconBadge(Icons.Outlined.Badge, size = 48.dp)
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Text("Enter your invite code", style = MaterialTheme.typography.titleMedium, color = Fp.palette.ink)
            Text("Your manager sent it by email, text or WhatsApp.", style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted)
          }
        }
        FpTextField(
          code,
          { code = it.uppercase().take(12); info = null },
          "Invite code, e.g. ABCD-2345",
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
          modifier = Modifier.testTag("inviteCode"),
        )
        if (showServer) FpTextField(server, { server = it }, "Server address (from the invite message)", leadingIcon = Icons.Outlined.Dns, modifier = Modifier.testTag("joinServer"))
        if (info == null) {
          PrimaryButton("Continue", onClick = { vm.checkInvite(code, server) { info = it } }, enabled = code.count { it.isLetterOrDigit() } >= 8, loading = state.busy, tall = true, modifier = Modifier.testTag("checkCode"))
          if (!showServer) LinkButton("Server settings", onClick = { showServer = true }, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("joinServerToggle"), color = Fp.palette.muted)
        }
        ErrorText(state.error)
      }
      info?.let { i ->
        SectionCard {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(i.name, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text(i.name, style = MaterialTheme.typography.titleMedium, color = Fp.palette.ink)
              Text(i.email, style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted)
            }
          }
          Banner("${i.companyName} invited ${i.name} as ${if (i.role == "tech") "a technician" else "an ${i.role}"}. Not you? Ask your manager for your own code.", BannerKind.INFO)
        }
        SectionCard(title = "Choose a password") {
          FpTextField(pw, { pw = it }, "Password (10 or more characters)", visualTransformation = PasswordVisualTransformation(), leadingIcon = Icons.Outlined.Lock, modifier = Modifier.testTag("newPassword"))
          FpTextField(pw2, { pw2 = it }, "Password again", visualTransformation = PasswordVisualTransformation(), leadingIcon = Icons.Outlined.Lock, modifier = Modifier.testTag("newPassword2"))
          if (pw2.isNotEmpty() && pw != pw2) ErrorText("The two passwords don't match.")
          PrimaryButton("Create my account", onClick = { vm.acceptInvite(code, pw) }, enabled = pw.length >= 10 && pw == pw2, loading = state.busy, tall = true, modifier = Modifier.testTag("createAccount"))
          Text("Next time, sign in with ${i.email} and this password.", style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted)
        }
      }
    }
  }
}

// ---- First-run setup: permissions, camera choice, glasses connection, live video test -------------

private enum class Step(val number: Int, val title: String, val subtitle: String) {
  PERMISSIONS(1, "Allow access", "FieldPartner needs a few permissions to hear you and record the visit."),
  CAMERA(2, "Choose your camera", "Pick what you will wear on the job. You can change it later in the Glasses tab."),
  CONNECT(3, "Connect your glasses", "Do these in order. Each one turns green when it's done."),
  TEST(4, "Test the video", "Make sure the AI can see what you see."),
  READY(4, "You're ready", "Here is how to work with your AI partner."),
}

@Composable
fun SetupScreen(vm: AppViewModel, activity: MainActivity) {
  val settings by vm.app.settings.values.collectAsStateWithLifecycle()
  var step by remember { mutableStateOf(Step.PERMISSIONS) }
  val back: (() -> Unit)? =
    when (step) {
      Step.CAMERA -> { { step = Step.PERMISSIONS } }
      Step.CONNECT -> { { step = Step.CAMERA } }
      Step.TEST -> { { step = if (settings.videoSource == VideoSource.GLASSES) Step.CONNECT else Step.CAMERA } }
      else -> null
    }
  FpScaffold("Set up FieldPartner", onBack = back) { mod ->
    PageColumn(mod) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        StepProgress(if (step == Step.READY) 4 else step.number, 4)
        Text(if (step == Step.READY) "Setup complete" else "Step ${step.number} of 4", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(step.title, style = MaterialTheme.typography.headlineSmall, color = Fp.palette.ink)
        Text(step.subtitle, style = MaterialTheme.typography.bodyMedium, color = Fp.palette.muted)
      }
      when (step) {
        Step.PERMISSIONS -> PermissionsStep(vm, activity) { step = Step.CAMERA }
        Step.CAMERA -> CameraStep(vm, activity) { src -> step = if (src == VideoSource.GLASSES) Step.CONNECT else Step.TEST }
        Step.CONNECT -> ConnectStep(vm, activity) { step = Step.TEST }
        Step.TEST -> TestStep(vm, activity, settings.videoSource) { step = Step.READY }
        Step.READY -> ReadyStep(vm)
      }
      if (step != Step.READY) {
        LinkButton("Skip for now. Run it later from the Menu.", onClick = { vm.finishSetup() }, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("skipSetup"), color = Fp.palette.muted)
      }
    }
  }
}

/** Step marker: a small lettered badge until done, then a green check. */
@Composable
private fun StepBadge(mark: String?, ok: Boolean) {
  if (ok || mark == null) {
    StatusIcon(if (ok) Status.OK else Status.PENDING, Modifier.size(26.dp))
  } else {
    Box(Modifier.size(26.dp).background(Fp.palette.chip, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
      Text(mark, style = MaterialTheme.typography.labelMedium, color = Fp.palette.ink)
    }
  }
}

@Composable
private fun StatusLine(label: String, ok: Boolean, detail: String? = null, mark: String? = null) {
  Row(verticalAlignment = Alignment.Top) {
    StepBadge(mark, ok)
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.titleSmall, color = if (ok) Fp.palette.muted else Fp.palette.ink)
      detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted) }
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
  SectionCard(padding = PaddingValues(vertical = 4.dp)) {
    perms.forEachIndexed { idx, pm ->
      if (idx > 0) RowDivider()
      ListRow(pm.label, subtitle = pm.why, icon = pm.icon, iconTint = MaterialTheme.colorScheme.primary, iconBackground = Fp.palette.blueTint, trailing = {
        if (has(pm.first)) StatusIcon(Status.OK, Modifier.size(22.dp)) else Text("Not yet", style = MaterialTheme.typography.labelMedium, color = Fp.palette.faint)
      })
    }
  }
  if (!has(Manifest.permission.RECORD_AUDIO) && tick > 0) {
    ErrorText("The microphone is required. If Android stopped asking, open the app permissions and turn it on.")
    SecondaryButton("Open app permissions", onClick = { activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))) })
  }
  val allMicOk = has(Manifest.permission.RECORD_AUDIO)
  if (!allMicOk || perms.any { !has(it.first) }) {
    PrimaryButton(
      "Allow access",
      onClick = {
        activity.requestPermissions((perms.map { it.first } + Manifest.permission.ACCESS_COARSE_LOCATION).toTypedArray()) {
          if (has(Manifest.permission.BLUETOOTH_CONNECT)) runCatching { vm.app.glasses.initialize() }
          tick++
        }
      },
      icon = Icons.Outlined.Security,
      tall = true,
      modifier = Modifier.testTag("allowPermissions"),
    )
  }
  if (allMicOk) PrimaryButton("Continue", onClick = onNext, icon = Icons.AutoMirrored.Filled.ArrowForward, tall = true, modifier = Modifier.testTag("permissionsNext"))
  else SecondaryButton("Continue", onClick = onNext, enabled = false, modifier = Modifier.testTag("permissionsNext"))
}

@Composable
private fun CameraStep(vm: AppViewModel, activity: MainActivity, onNext: (VideoSource) -> Unit) {
  val s by vm.app.settings.values.collectAsStateWithLifecycle()
  val showSimulated = AppSettings.isEmulator() || BuildConfig.DEBUG
  val options = buildList {
    add(Triple(VideoSource.GLASSES, "Meta glasses" to "Ray-Ban Meta or Oakley Meta. Hands free: the AI sees what you see.", Icons.Outlined.Visibility))
    add(Triple(VideoSource.PHONE_CAMERA, "Phone camera" to "No glasses yet. Uses the phone's back camera. Good for trying the app.", Icons.Outlined.PhotoCamera))
    if (showSimulated) add(Triple(VideoSource.SIMULATED_GLASSES, "Simulated glasses" to "Plays a demo video through Meta's glasses simulator. For testing and the emulator.", Icons.Outlined.PlayCircle))
  }
  options.forEach { (src, text, icon) ->
    ChoiceCard(text.first, text.second, s.videoSource == src, { vm.app.settings.update { it.copy(videoSource = src) } }, icon = icon, modifier = Modifier.testTag("src_${src.name}"))
  }
  PrimaryButton(
    "Continue",
    onClick = {
      if (s.videoSource != VideoSource.SIMULATED_GLASSES && vm.app.glasses.status.value.simulated) vm.app.glasses.stopSimulated()
      if (s.videoSource == VideoSource.PHONE_CAMERA && activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        activity.requestPermissions(arrayOf(Manifest.permission.CAMERA)) { onNext(s.videoSource) }
      } else {
        onNext(s.videoSource)
      }
    },
    icon = Icons.AutoMirrored.Filled.ArrowForward,
    tall = true,
    modifier = Modifier.testTag("cameraNext"),
  )
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

  SectionCard {
    StatusLine(mark = "A", label = "Meta AI app installed, glasses paired in it", ok = metaInstalled, detail = "Open Meta AI, sign in with your Meta account, tap the glasses icon and follow its pairing steps.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SecondaryButton(if (metaInstalled) "Update Meta AI" else "Get Meta AI", onClick = {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$META_AI_PACKAGE"))
        runCatching { ctx.startActivity(market) }.onFailure { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$META_AI_PACKAGE"))) }
      }, fill = false, modifier = Modifier.weight(1f))
      if (metaInstalled) SecondaryButton("Open Meta AI", onClick = { ctx.packageManager.getLaunchIntentForPackage(META_AI_PACKAGE)?.let { ctx.startActivity(it) } }, fill = false, modifier = Modifier.weight(1f))
    }
  }
  SectionCard {
    StatusLine(mark = "B", label = "Developer Mode on in Meta AI", ok = gs.isRegistered, detail = "In Meta AI: Settings > App info > tap the version number 5 times > turn on Developer Mode.")
  }
  SectionCard {
    StatusLine(mark = "C", label = "FieldPartner connected to your glasses", ok = gs.isRegistered, detail = "Meta AI opens and asks to allow FieldPartner. Tap Allow, then come back here.")
    PrimaryButton(if (gs.isRegistered) "Connected. Connect again" else "Connect", onClick = { vm.app.glasses.clearError(); vm.app.glasses.register(activity) }, icon = Icons.Outlined.Link, modifier = Modifier.testTag("connectGlasses"))
  }
  SectionCard {
    StatusLine(mark = "D", label = "Glasses camera allowed", ok = cameraOk, detail = "Meta AI asks once. Choose Allow always.")
    SecondaryButton("Allow camera", onClick = { activity.requestGlassesCamera() }, icon = Icons.Outlined.PhotoCamera, enabled = gs.isRegistered)
  }
  SectionCard {
    StatusLine(
      mark = "E",
      label = "Glasses found",
      ok = gs.deviceCount > 0,
      detail = if (gs.deviceCount > 0) "${gs.deviceName ?: "Glasses"}${gs.battery?.let { ", battery $it%" } ?: ""}" else "Take the glasses out of the case, unfold them and put them on. Bluetooth must be on.",
    )
  }
  message?.let { Banner(it, BannerKind.INFO) }
  gs.lastError?.let { ErrorText(it) }
  PrimaryButton("Continue to video test", onClick = onNext, enabled = gs.isRegistered && cameraOk, icon = Icons.AutoMirrored.Filled.ArrowForward, tall = true, modifier = Modifier.testTag("connectNext"))
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
    SectionCard {
      Text("The phone camera turns on when you start a visit. Hold the phone or use a chest mount with the back camera facing the work.", style = MaterialTheme.typography.bodyLarge, color = Fp.palette.ink)
      StatusLine("Camera permission", activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    PrimaryButton("Continue", onClick = onNext, icon = Icons.AutoMirrored.Filled.ArrowForward, tall = true, modifier = Modifier.testTag("testNext"))
    return
  }
  val received = (gs.frames - startFrames).coerceAtLeast(0)
  val ok = running && received >= 15
  SectionCard(padding = PaddingValues(0.dp)) {
    Box(Modifier.fillMaxWidth().height(260.dp).background(Color.Black, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)), contentAlignment = Alignment.Center) {
      val bmp = preview
      if (running && bmp != null) Image(bmp.asImageBitmap(), "Live view from the glasses", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(260.dp))
      else Text(if (running) "Waiting for video..." else "Tap Start test", color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(
        if (source == VideoSource.SIMULATED_GLASSES) "Plays the demo video through Meta's glasses simulator, the same path real glasses use."
        else "Put the glasses on and look at something. You should see it here within 10 seconds.",
        style = MaterialTheme.typography.bodyMedium,
        color = Fp.palette.muted,
      )
      StatusLine("Video received", ok, if (running) "$received frames, stream ${gs.streamState.lowercase()}" else null)
      gs.lastError?.let { ErrorText(it) }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        PrimaryButton(
          "Start test",
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
          icon = Icons.Filled.PlayArrow,
          modifier = Modifier.weight(1f).testTag("startTest"),
        )
        SecondaryButton("Stop", onClick = {
          vm.app.glasses.previewEnabled = false
          vm.app.glasses.stopStreaming()
          running = false
        }, enabled = running, icon = Icons.Filled.Stop, fill = false, modifier = Modifier.weight(1f))
      }
    }
  }
  if (running && !ok) {
    Banner("No video after 20 seconds? Check the glasses are unfolded and worn, Bluetooth is on, step D was allowed, and the phone is close. Then Stop and Start again.", BannerKind.WARN)
  }
  PrimaryButton("Continue", onClick = onNext, enabled = ok, icon = Icons.AutoMirrored.Filled.ArrowForward, tall = true, modifier = Modifier.testTag("testNext"))
}

/** The short how-to list. Shown at the end of setup and from Menu > How to use FieldPartner. */
@Composable
fun HowToTips(companyWake: String, override: String) {
  val wake = override.ifBlank { companyWake }
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
    Row(verticalAlignment = Alignment.Top) {
      IconBadge(icon, size = 32.dp)
      Spacer(Modifier.width(12.dp))
      Text(text, style = MaterialTheme.typography.bodyMedium, color = Fp.palette.ink, modifier = Modifier.padding(top = 6.dp))
    }
  }
}

@Composable
private fun ReadyStep(vm: AppViewModel) {
  val state by vm.state.collectAsStateWithLifecycle()
  SectionCard {
    HowToTips(state.company?.wake_phrase ?: "hey dispatch", vm.app.settings.current.wakePhraseOverride)
  }
  PrimaryButton("Go to my jobs", onClick = { vm.finishSetup() }, icon = Icons.AutoMirrored.Filled.ArrowForward, tall = true, modifier = Modifier.testTag("finishSetup"))
}
