package com.fieldpartner.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Plumbing
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Roofing
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fieldpartner.app.AppSettings
import com.fieldpartner.app.BuildConfig
import com.fieldpartner.app.MainActivity
import com.fieldpartner.app.VideoSource
import com.fieldpartner.app.core.Job
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

// ---- Shared helpers ----------------------------------------------------------------------------

/** One icon per trade, used on job cards and headers. */
fun tradeIcon(trade: String): ImageVector =
  when (trade.lowercase()) {
    "hvac" -> Icons.Outlined.AcUnit
    "electrical" -> Icons.Outlined.ElectricBolt
    "plumbing" -> Icons.Outlined.Plumbing
    "roofing" -> Icons.Outlined.Roofing
    "cleaning" -> Icons.Outlined.CleaningServices
    "auto" -> Icons.Outlined.DirectionsCar
    "appliance" -> Icons.Outlined.Kitchen
    else -> Icons.Outlined.Handyman
  }

fun tradeName(trade: String): String =
  when (trade.lowercase()) {
    "hvac" -> "HVAC"
    else -> trade.replace('_', ' ').replaceFirstChar { it.uppercase() }
  }

private fun timeOf(iso: String?): String =
  iso?.let {
    runCatching {
        val t = OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault())
        val day = if (t.toLocalDate() == LocalDate.now()) "Today" else t.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        "$day at ${t.format(DateTimeFormatter.ofPattern("h:mm a"))}"
      }
      .getOrNull()
  } ?: ""

private fun lastVisitPill(job: Job): Triple<String, Status, String>? {
  val lv = job.last_visit ?: return null
  return when {
    lv.report_status == "approved" || lv.report_status == "sent" -> Triple("Report sent", Status.OK, "ok")
    lv.ended_at != null -> Triple("Report to review", Status.WARN, "warn")
    else -> Triple("Visit in progress", Status.PENDING, "info")
  }
}

private fun openMaps(ctx: android.content.Context, addr: String) {
  runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(addr)))) }
}

// ---- Sign in -------------------------------------------------------------------------------------

@Composable
fun LoginScreen(vm: AppViewModel) {
  val state by vm.state.collectAsStateWithLifecycle()
  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var server by remember { mutableStateOf(vm.app.settings.current.apiBaseUrl) }
  // The live server is built in. Show the address only for local testing builds or on request.
  var showServer by remember { mutableStateOf(!vm.app.settings.current.apiBaseUrl.startsWith("https://")) }
  Box(Modifier.fillMaxSize().background(Fp.palette.canvas)) {
    Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Spacer(Modifier.height(36.dp))
      Text("FieldPartner", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
      Text("Your AI partner on every job", style = MaterialTheme.typography.bodyLarge, color = Fp.palette.muted, textAlign = TextAlign.Center)
      Spacer(Modifier.height(8.dp))
      SectionCard(padding = PaddingValues(16.dp)) {
        FpTextField(email, { email = it }, "Email", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.testTag("email"))
        FpTextField(password, { password = it }, "Password", visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.testTag("password"))
        if (showServer) {
          FpTextField(server, { server = it }, "Server address", supportingText = "Change only if your manager gave you a different address.", leadingIcon = Icons.Outlined.Dns, modifier = Modifier.testTag("server"))
        }
        PrimaryButton("Sign in", onClick = { vm.login(email, password, server) }, enabled = email.isNotBlank() && password.isNotBlank(), loading = state.busy, tall = true, modifier = Modifier.testTag("signin"))
        ErrorText(state.error)
        if (!showServer) LinkButton("Server settings", onClick = { showServer = true }, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("serverToggle"), color = Fp.palette.muted)
      }
      Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = Fp.palette.line)
        Text("New technician?", style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted, modifier = Modifier.padding(horizontal = 12.dp))
        HorizontalDivider(Modifier.weight(1f), color = Fp.palette.line)
      }
      SecondaryButton("I have an invite code", onClick = { vm.clearError(); vm.push(Screen.Join) }, icon = Icons.Outlined.Badge, contentColor = MaterialTheme.colorScheme.primary, modifier = Modifier.height(52.dp).testTag("haveInvite"))
      Text("No code yet? Your manager creates one on the FieldPartner portal under Team.", style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted, textAlign = TextAlign.Center)
    }
  }
}

// ---- Jobs (home tab) -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(vm: AppViewModel, onStart: ((Job) -> Unit)? = null) {
  val state by vm.state.collectAsStateWithLifecycle()
  val pending by vm.app.pendingUploads.collectAsStateWithLifecycle()
  val ctx = LocalContext.current
  LaunchedEffect(Unit) { vm.refreshJobs() }
  FpScaffold(
    "FieldPartner",
    bigTitle = true,
    titleColor = MaterialTheme.colorScheme.primary,
    tab = Tab.JOBS,
    onTab = vm::openTab,
    actions = {
      CircleIconButton(Icons.Default.Add, "Walk-in job", onClick = { vm.push(Screen.WalkIn) })
      CircleIconButton(Icons.Default.Refresh, "Refresh", onClick = { vm.refreshJobs() })
    },
  ) { mod ->
    PullToRefreshBox(isRefreshing = state.loadingJobs && state.jobs.isNotEmpty(), onRefresh = { vm.refreshJobs() }, modifier = mod) {
      LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Composer(vm) }
        if (pending > 0 || !state.error.isNullOrBlank()) {
          item {
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              if (pending > 0) Banner("$pending recording file${if (pending == 1) "" else "s"} waiting to upload. They send automatically on Wi-Fi.", BannerKind.INFO)
              ErrorText(state.error)
            }
          }
        }
        item {
          Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your jobs", style = MaterialTheme.typography.titleLarge, color = Fp.palette.ink, modifier = Modifier.weight(1f))
            if (state.jobs.isNotEmpty()) Pill("${state.jobs.size}", MaterialTheme.colorScheme.primary, Fp.palette.blueTint)
          }
        }
        if (state.loadingJobs && state.jobs.isEmpty()) item { Loading() }
        if (!state.loadingJobs && state.jobs.isEmpty()) {
          item {
            SectionCard(rounded = false) {
              EmptyState(Icons.Outlined.EventNote, "No jobs scheduled", "Jobs from the office show up here. Got a customer in front of you? Start a walk-in job.") {
                Spacer(Modifier.height(4.dp))
                PrimaryButton("New walk-in job", onClick = { vm.push(Screen.WalkIn) }, icon = Icons.Default.Add)
              }
            }
          }
        }
        items(state.jobs, key = { it.id }) { job ->
          JobCard(
            job,
            onOpen = { vm.push(Screen.JobDetail(job)) },
            onStart = { if (onStart != null) onStart(job) else vm.push(Screen.JobDetail(job)) },
            onDirections = job.address?.let { a -> { openMaps(ctx, a) } },
          )
        }
      }
    }
  }
}

/** Top card on the home tab: who you are, a quick way to start a walk-in job, and the glasses status. */
@Composable
private fun Composer(vm: AppViewModel) {
  val state by vm.state.collectAsStateWithLifecycle()
  Column(Modifier.fillMaxWidth().background(Fp.palette.card)) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Avatar(state.user?.name)
      Spacer(Modifier.width(10.dp))
      Box(
        Modifier.weight(1f).height(40.dp).background(Fp.palette.canvas, RoundedCornerShape(20.dp)).clickable { vm.push(Screen.WalkIn) }.padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
      ) {
        Text("Start a walk-in job", style = MaterialTheme.typography.bodyMedium, color = Fp.palette.muted)
      }
    }
    HorizontalDivider(color = Fp.palette.line)
    GlassesStatusRow(vm)
  }
}

@Composable
fun GlassesStatusRow(vm: AppViewModel) {
  val gs by vm.app.glasses.status.collectAsStateWithLifecycle()
  val settings by vm.app.settings.values.collectAsStateWithLifecycle()
  val (label, status) =
    when (settings.videoSource) {
      VideoSource.PHONE_CAMERA -> "Video from the phone camera" to Status.OK
      VideoSource.SIMULATED_GLASSES -> "Simulated glasses (demo video)" to Status.OK
      VideoSource.GLASSES ->
        when {
          !gs.isRegistered -> "Glasses not connected. Tap to connect." to Status.WARN
          gs.deviceCount == 0 -> "Glasses connected, not in range" to Status.WARN
          else -> "${gs.deviceName ?: "Glasses"} ready${gs.battery?.let { " · battery $it%" } ?: ""}" to Status.OK
        }
    }
  Row(Modifier.fillMaxWidth().clickable { vm.openTab(Tab.GLASSES) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    StatusIcon(status)
    Spacer(Modifier.width(10.dp))
    Text(label, style = MaterialTheme.typography.bodyMedium, color = Fp.palette.ink, modifier = Modifier.weight(1f))
    Text("Glasses", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
  }
}

@Composable
fun JobCard(job: Job, onOpen: () -> Unit, onStart: () -> Unit = onOpen, onDirections: (() -> Unit)? = null) {
  Column(Modifier.fillMaxWidth().background(Fp.palette.card)) {
    Column(Modifier.clickable(onClick = onOpen).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(tradeIcon(job.trade))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(job.title, style = MaterialTheme.typography.titleMedium, color = Fp.palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
          Text(listOf(timeOf(job.scheduled_at).ifEmpty { "Walk-in" }, tradeName(job.trade)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted)
        }
        lastVisitPill(job)?.let { (text, st, _) ->
          val (fg, bg) =
            when (st) {
              Status.OK -> FpColors.Success to Fp.palette.successTint
              Status.WARN -> FpColors.Warning to Fp.palette.warningTint
              else -> MaterialTheme.colorScheme.primary to Fp.palette.blueTint
            }
          Spacer(Modifier.width(8.dp))
          Pill(text, fg, bg)
        }
      }
      job.complaint?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Fp.palette.ink, maxLines = 3, overflow = TextOverflow.Ellipsis) }
      val meta = listOfNotNull(job.customer_name, job.address).joinToString(" · ")
      if (meta.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(if (job.address != null) Icons.Outlined.Place else Icons.Outlined.Person, contentDescription = null, tint = Fp.palette.faint, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text(meta, style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
    CardActions {
      CardAction(Icons.Filled.PlayArrow, "Start", onStart, primary = true)
      if (onDirections != null) CardAction(Icons.Outlined.Map, "Directions", onDirections)
      CardAction(Icons.Outlined.Description, "Details", onOpen)
    }
  }
}

// ---- Job detail ------------------------------------------------------------------------------------

@Composable
fun JobDetailScreen(vm: AppViewModel, job: Job, onStart: (Job) -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val ctx = LocalContext.current
  FpScaffold("Job details", onBack = { vm.back() }) { mod ->
    PageColumn(mod) {
      SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconBadge(tradeIcon(job.trade), size = 48.dp)
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Text(job.title, style = MaterialTheme.typography.titleLarge, color = Fp.palette.ink)
            Text(listOf(tradeName(job.trade), timeOf(job.scheduled_at).ifEmpty { "Walk-in" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted)
          }
        }
        job.complaint?.takeIf { it.isNotBlank() }?.let {
          Text("Customer says", style = MaterialTheme.typography.labelMedium, color = Fp.palette.muted)
          Text(it, style = MaterialTheme.typography.bodyLarge, color = Fp.palette.ink)
        }
        PrimaryButton(
          if (vm.visit != null) "Return to visit" else "Start visit",
          onClick = { onStart(job) },
          icon = Icons.Filled.PlayArrow,
          loading = state.busy,
          tall = true,
          modifier = Modifier.testTag("startVisit"),
        )
        job.last_visit?.takeIf { it.ended_at != null }?.let { lv -> SecondaryButton("Review last report", onClick = { vm.push(Screen.Report(lv.id)) }, icon = Icons.Outlined.Description) }
        ErrorText(state.error)
      }
      if (job.customer_name != null || job.address != null || job.customer_phone != null) {
        SectionCard(title = "Customer") {
          job.customer_name?.let { InfoRow(Icons.Outlined.Person, "Name", it) }
          job.customer_phone?.let { ph ->
            InfoRow(Icons.Outlined.Phone, "Phone", ph, trailing = { LinkButton("Call", onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$ph"))) } }) })
          }
          job.address?.let { addr -> InfoRow(Icons.Outlined.Place, "Address", addr, trailing = { LinkButton("Directions", onClick = { openMaps(ctx, addr) }) }) }
        }
      }
      if (job.access_notes != null || job.recording_pref != null) {
        SectionCard(title = "Before you go in") {
          job.access_notes?.let { InfoRow(Icons.Outlined.Lock, "Access notes", it) }
          job.recording_pref?.let { InfoRow(Icons.Outlined.Videocam, "Customer recording preference", it) }
        }
      }
      val trade = state.trades.firstOrNull { it.id == job.trade }?.profile
      trade?.preWorkReadings?.takeIf { it.isNotEmpty() }?.let { r ->
        SectionCard(title = "Safety readings before work") {
          r.forEach { ListLine(Icons.Outlined.Speed, "${it.label}${it.unit?.let { u -> " ($u)" } ?: ""}") }
        }
      }
      trade?.stopRules?.takeIf { it.isNotEmpty() }?.let { rules ->
        SectionCard(title = "Stop and call the expert if") {
          rules.forEach { ListLine(Icons.Outlined.PanTool, it, tint = FpColors.Danger) }
        }
      }
    }
  }
}

@Composable
private fun ListLine(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color? = null) {
  Row(verticalAlignment = Alignment.Top) {
    Icon(icon, contentDescription = null, tint = tint ?: Fp.palette.muted, modifier = Modifier.padding(top = 2.dp).size(18.dp))
    Spacer(Modifier.width(10.dp))
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Fp.palette.ink)
  }
}

@Composable
fun ListBlock(label: String, items: List<String>, icon: ImageVector) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(label, style = MaterialTheme.typography.titleSmall, color = Fp.palette.ink)
    items.forEach { ListLine(icon, it) }
  }
}

@Composable
fun LabeledBlock(label: String, text: String) {
  Column {
    Text(label, style = MaterialTheme.typography.labelMedium, color = Fp.palette.muted)
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Fp.palette.ink)
  }
}

// ---- Walk-in job ---------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkInScreen(vm: AppViewModel) {
  val state by vm.state.collectAsStateWithLifecycle()
  val trades = state.trades.ifEmpty { null }
  var trade by remember { mutableStateOf(state.user?.trades?.firstOrNull() ?: trades?.firstOrNull()?.id ?: "general") }
  var title by remember { mutableStateOf("") }
  var complaint by remember { mutableStateOf("") }
  var address by remember { mutableStateOf("") }
  var name by remember { mutableStateOf("") }
  var phone by remember { mutableStateOf("") }
  var open by remember { mutableStateOf(false) }
  val tradeOptions = trades?.map { it.id to it.name } ?: listOf("hvac" to "HVAC", "electrical" to "Electrical", "plumbing" to "Plumbing", "roofing" to "Roofing", "cleaning" to "Cleaning", "auto" to "Auto", "appliance" to "Appliance", "general" to "General")
  FpScaffold("New walk-in job", onBack = { vm.back() }) { mod ->
    PageColumn(mod) {
      SectionCard(title = "The job") {
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
          FpTextField(
            value = tradeOptions.firstOrNull { it.first == trade }?.second ?: tradeName(trade),
            onValueChange = {},
            label = "Trade",
            readOnly = true,
            leadingIcon = tradeIcon(trade),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
          )
          ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Fp.palette.card) {
            tradeOptions.forEach { (id, n) ->
              DropdownMenuItem(
                text = { Text(n, style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = { Icon(tradeIcon(id), contentDescription = null, tint = Fp.palette.muted) },
                onClick = {
                  trade = id
                  open = false
                },
              )
            }
          }
        }
        FpTextField(title, { title = it }, "Job title, e.g. No heat")
        FpTextField(complaint, { complaint = it }, "What the customer says is wrong", singleLine = false, minLines = 3)
      }
      SectionCard(title = "Customer") {
        FpTextField(name, { name = it }, "Customer name", leadingIcon = Icons.Outlined.Person)
        FpTextField(phone, { phone = it }, "Customer phone", leadingIcon = Icons.Outlined.Phone, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        FpTextField(address, { address = it }, "Address", leadingIcon = Icons.Outlined.Place, singleLine = false)
      }
      PrimaryButton("Create job", onClick = { vm.createWalkIn(trade, title.trim(), complaint.trim(), address.trim(), name, phone) }, enabled = title.isNotBlank(), loading = state.busy, tall = true)
      ErrorText(state.error)
    }
  }
}

// ---- Glasses tab ---------------------------------------------------------------------------------

@Composable
fun GlassesScreen(vm: AppViewModel, activity: MainActivity) {
  val s by vm.app.settings.values.collectAsStateWithLifecycle()
  val gs by vm.app.glasses.status.collectAsStateWithLifecycle()
  val ctx = LocalContext.current
  var message by remember { mutableStateOf<String?>(null) }
  activity.glassesPermissionMessage = { message = it }
  val metaInstalled = runCatching { ctx.packageManager.getPackageInfo(META_AI_PACKAGE, 0) }.isSuccess
  val pickVideo =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        vm.app.settings.update { it.copy(simulatedVideoUri = uri.toString()) }
        message = "Demo video set. It must be H.265 (HEVC)."
      }
    }
  LaunchedEffect(Unit) { if (s.videoSource == VideoSource.GLASSES) runCatching { vm.app.glasses.initialize() } }
  FpScaffold("Glasses", bigTitle = true, tab = Tab.GLASSES, onTab = vm::openTab) { mod ->
    PageColumn(mod) {
      // Status header.
      val (headline, sub, st) =
        when (s.videoSource) {
          VideoSource.PHONE_CAMERA -> Triple("Phone camera", "Video comes from the phone's back camera.", Status.OK)
          VideoSource.SIMULATED_GLASSES -> Triple("Simulated glasses", "A demo video plays through Meta's glasses simulator.", Status.OK)
          VideoSource.GLASSES ->
            when {
              !gs.isRegistered -> Triple("Meta glasses", "Not connected yet. Follow the steps below.", Status.WARN)
              gs.deviceCount == 0 -> Triple(gs.deviceName ?: "Meta glasses", "Connected to the app. Put the glasses on so the phone can find them.", Status.WARN)
              else -> Triple(gs.deviceName ?: "Meta glasses", "Ready${gs.battery?.let { " · battery $it%" } ?: ""}", Status.OK)
            }
        }
      SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconBadge(if (s.videoSource == VideoSource.PHONE_CAMERA) Icons.Outlined.Smartphone else Icons.Outlined.Visibility, size = 56.dp)
          Spacer(Modifier.width(14.dp))
          Column(Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.titleLarge, color = Fp.palette.ink)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted)
          }
          StatusIcon(st, Modifier.size(24.dp))
        }
        message?.let { Banner(it, BannerKind.INFO) }
        gs.lastError?.let { ErrorText(it) }
      }

      GroupLabel("What you wear on the job")
      val showSimulated = AppSettings.isEmulator() || BuildConfig.DEBUG
      ChoiceCard("Meta glasses", "Hands free. The AI sees what you see.", s.videoSource == VideoSource.GLASSES, { vm.app.settings.update { it.copy(videoSource = VideoSource.GLASSES) } }, icon = Icons.Outlined.Visibility)
      ChoiceCard("Phone camera", "No glasses yet. Uses the back camera.", s.videoSource == VideoSource.PHONE_CAMERA, { vm.app.settings.update { it.copy(videoSource = VideoSource.PHONE_CAMERA) } }, icon = Icons.Outlined.PhotoCamera)
      if (showSimulated || s.videoSource == VideoSource.SIMULATED_GLASSES) {
        ChoiceCard("Simulated glasses", "Demo video for testing and the emulator.", s.videoSource == VideoSource.SIMULATED_GLASSES, { vm.app.settings.update { it.copy(videoSource = VideoSource.SIMULATED_GLASSES) } }, icon = Icons.Outlined.VideoLibrary)
      }

      if (s.videoSource == VideoSource.GLASSES) {
        GroupLabel("Connection")
        SectionCard(padding = PaddingValues(vertical = 4.dp)) {
          ListRow(if (metaInstalled) "Open Meta AI" else "Get the Meta AI app", subtitle = "Pair your glasses there first", icon = Icons.Outlined.OpenInNew, onClick = {
            if (metaInstalled) ctx.packageManager.getLaunchIntentForPackage(META_AI_PACKAGE)?.let { ctx.startActivity(it) }
            else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$META_AI_PACKAGE"))) }.onFailure { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$META_AI_PACKAGE"))) }
          })
          RowDivider()
          ListRow(if (gs.isRegistered) "Reconnect glasses" else "Connect Meta glasses", subtitle = "Meta AI opens and asks you to allow FieldPartner", icon = Icons.Outlined.Link, iconTint = MaterialTheme.colorScheme.primary, iconBackground = Fp.palette.blueTint, onClick = { vm.app.glasses.clearError(); vm.app.glasses.register(activity) })
          RowDivider()
          ListRow("Allow glasses camera", subtitle = "Choose Allow always", icon = Icons.Outlined.PhotoCamera, enabled = gs.isRegistered, onClick = { activity.requestGlassesCamera() })
        }
        Banner("Meta AI needs Developer Mode on: Settings > App info > tap the version 5 times.", BannerKind.INFO)
      }
      if (s.videoSource == VideoSource.SIMULATED_GLASSES) {
        GroupLabel("Demo video")
        SectionCard(padding = PaddingValues(vertical = 4.dp)) {
          ListRow("Pick a video", subtitle = if (s.simulatedVideoUri.isBlank()) "Using the built-in furnace video" else "Using: ${s.simulatedVideoUri.takeLast(32)}", icon = Icons.Outlined.VideoLibrary, onClick = { pickVideo.launch(arrayOf("video/*")) })
          if (s.simulatedVideoUri.isNotBlank()) {
            RowDivider()
            ListRow("Use the built-in video", icon = Icons.Outlined.Refresh, onClick = { vm.app.settings.update { it.copy(simulatedVideoUri = "") } })
          }
        }
      }
      GroupLabel("Check it works")
      SectionCard(padding = PaddingValues(vertical = 4.dp)) {
        ListRow("Test video and run setup", subtitle = "Permissions, connection and a live video check", icon = Icons.Outlined.Tune, enabled = vm.visit == null, onClick = { vm.push(Screen.Setup) })
      }
    }
  }
}

// ---- Menu tab (settings and account) -------------------------------------------------------------

@Composable
fun SettingsScreen(vm: AppViewModel, activity: MainActivity) {
  val state by vm.state.collectAsStateWithLifecycle()
  val s by vm.app.settings.values.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  var server by remember { mutableStateOf(s.apiBaseUrl) }
  var wake by remember { mutableStateOf(s.wakePhraseOverride) }
  var message by remember { mutableStateOf<String?>(null) }
  var showServer by remember { mutableStateOf(false) }
  var showHelp by remember { mutableStateOf(false) }
  @Suppress("UNUSED_VARIABLE") val keep = activity
  FpScaffold("Menu", bigTitle = true, tab = Tab.MENU, onTab = vm::openTab) { mod ->
    PageColumn(mod) {
      // Profile.
      SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Avatar(state.user?.name, size = 56.dp)
          Spacer(Modifier.width(14.dp))
          Column(Modifier.weight(1f)) {
            Text(state.user?.name ?: "Signed in", style = MaterialTheme.typography.titleMedium, color = Fp.palette.ink)
            val role = when (state.user?.role) { "tech" -> "Technician"; "expert" -> "Expert"; "admin" -> "Admin"; null -> null; else -> state.user?.role }
            Text(listOfNotNull(role, state.company?.name).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted)
            state.user?.email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted) }
          }
        }
      }

      GroupLabel("AI partner")
      SectionCard {
        FpTextField(wake, { wake = it }, "Wake phrase", placeholder = state.company?.wake_phrase ?: "hey dispatch", leadingIcon = Icons.Outlined.RecordVoiceOver, supportingText = "Leave blank to use your company's. Say it before a question, e.g. \"${wake.ifBlank { state.company?.wake_phrase ?: "hey dispatch" }}, what does code 33 mean?\"")
        PrimaryButton("Save", onClick = {
          vm.app.settings.update { it.copy(wakePhraseOverride = wake.trim()) }
          message = "Saved."
        })
        message?.let { Banner(it, BannerKind.OK) }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.VolumeUp, contentDescription = null, tint = Fp.palette.muted, modifier = Modifier.size(20.dp))
          Spacer(Modifier.width(10.dp))
          Text("Voice volume", style = MaterialTheme.typography.titleSmall, color = Fp.palette.ink, modifier = Modifier.weight(1f))
          Text("${(s.aiVoiceVolume * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted)
        }
        Slider(
          value = s.aiVoiceVolume,
          onValueChange = { v -> vm.app.settings.update { it.copy(aiVoiceVolume = v) } },
          colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = Fp.palette.chip),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.Language, contentDescription = null, tint = Fp.palette.muted, modifier = Modifier.size(20.dp))
          Spacer(Modifier.width(10.dp))
          Text("Language", style = MaterialTheme.typography.titleSmall, color = Fp.palette.ink)
        }
        Segmented(listOf("en-US" to "English", "es-US" to "Español"), s.language, { code -> vm.app.settings.update { it.copy(language = code) } })
      }

      GroupLabel("During visits")
      SectionCard(padding = PaddingValues(vertical = 4.dp)) {
        SwitchRow("Show transcript", s.showTranscript, subtitle = "What you and the AI said, on screen", icon = Icons.Outlined.Subtitles) { v -> vm.app.settings.update { it.copy(showTranscript = v) } }
        RowDivider()
        SwitchRow("Volume buttons save a clip", s.volumeKeysAsClip, subtitle = "Marks the last moments of video", icon = Icons.Outlined.Mic) { v -> vm.app.settings.update { it.copy(volumeKeysAsClip = v) } }
        RowDivider()
        SwitchRow("Upload video on cellular", s.uploadVideoOnCellular, subtitle = "Off: waits for Wi-Fi", icon = Icons.Outlined.CloudUpload) { v -> vm.app.settings.update { it.copy(uploadVideoOnCellular = v) } }
      }

      GroupLabel("Help and account")
      SectionCard(padding = PaddingValues(vertical = 4.dp)) {
        ListRow("How to use FieldPartner", icon = Icons.AutoMirrored.Outlined.HelpOutline, onClick = { showHelp = true })
        RowDivider()
        ListRow("Run setup again", subtitle = "Permissions, glasses and video test", icon = Icons.Outlined.Tune, enabled = vm.visit == null, onClick = { vm.push(Screen.Setup) })
        RowDivider()
        ListRow("Server", subtitle = s.apiBaseUrl, icon = Icons.Outlined.Dns, onClick = { showServer = !showServer })
        if (showServer) {
          Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FpTextField(server, { server = it }, "Server address")
            SecondaryButton("Update server", onClick = {
              vm.app.setBaseUrl(server.trim())
              message = "Server updated."
            })
          }
        }
      }
      SecondaryButton("Sign out", onClick = { scope.launch { vm.logout() } }, icon = Icons.AutoMirrored.Outlined.Logout, enabled = vm.visit == null)
      Text("FieldPartner ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall, color = Fp.palette.faint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
  }
  if (showHelp) {
    AlertDialog(
      onDismissRequest = { showHelp = false },
      containerColor = Fp.palette.card,
      title = { Text("Working with your AI partner", style = MaterialTheme.typography.titleLarge) },
      text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { HowToTips(state.company?.wake_phrase ?: "hey dispatch", s.wakePhraseOverride) } },
      confirmButton = { LinkButton("Got it", onClick = { showHelp = false }) },
    )
  }
}
