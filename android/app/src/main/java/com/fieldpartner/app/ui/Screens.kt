package com.fieldpartner.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fieldpartner.app.BuildConfig
import com.fieldpartner.app.MainActivity
import com.fieldpartner.app.VideoSource
import com.fieldpartner.app.core.Job
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FpScaffold(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}, content: @Composable (Modifier) -> Unit) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = { actions() },
      )
    }
  ) { pad -> content(Modifier.padding(pad).fillMaxSize()) }
}

@Composable
fun ErrorText(msg: String?) {
  if (!msg.isNullOrBlank()) Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

// ---- Login -----------------------------------------------------------------------------------

@Composable
fun LoginScreen(vm: AppViewModel) {
  val state by vm.state.collectAsStateWithLifecycle()
  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var server by remember { mutableStateOf(vm.app.settings.current.apiBaseUrl) }
  Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Spacer(Modifier.height(40.dp))
    Text("FieldPartner", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    Text("AI partner for field technicians", style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("email"))
    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("password"))
    OutlinedTextField(server, { server = it }, label = { Text("Server address") }, singleLine = true, supportingText = { Text("Your office gives you this. On the emulator: http://<Mac IP>:8080") }, modifier = Modifier.fillMaxWidth().testTag("server"))
    Button(onClick = { vm.login(email, password, server) }, enabled = !state.busy && email.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp).testTag("signin")) {
      if (state.busy) CircularProgressIndicator(Modifier.width(22.dp)) else Text("Sign in")
    }
    ErrorText(state.error)
  }
}

// ---- Jobs ------------------------------------------------------------------------------------

@Composable
fun JobsScreen(vm: AppViewModel) {
  val state by vm.state.collectAsStateWithLifecycle()
  val pending by vm.app.pendingUploads.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { vm.refreshJobs() }
  FpScaffold(
    "Today",
    actions = {
      IconButton(onClick = { vm.refreshJobs() }) { Icon(Icons.Default.Refresh, "Refresh") }
      IconButton(onClick = { vm.push(Screen.WalkIn) }) { Icon(Icons.Default.Add, "Walk-in job") }
      IconButton(onClick = { vm.push(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
    },
  ) { mod ->
    LazyColumn(mod.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      item { GlassesStatusRow(vm) }
      item {
        Text("${state.user?.name ?: ""}  ${state.company?.name?.let { "· $it" } ?: ""}", style = MaterialTheme.typography.labelLarge)
        if (pending > 0) Text("$pending recording file(s) waiting to upload", style = MaterialTheme.typography.labelMedium)
        ErrorText(state.error)
      }
      if (state.loadingJobs && state.jobs.isEmpty()) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
      if (!state.loadingJobs && state.jobs.isEmpty()) item { Text("No jobs scheduled today. Tap + for a walk-in job.", Modifier.padding(16.dp)) }
      items(state.jobs, key = { it.id }) { job -> JobCard(job) { vm.push(Screen.JobDetail(job)) } }
    }
  }
}

@Composable
fun GlassesStatusRow(vm: AppViewModel) {
  val gs by vm.app.glasses.status.collectAsStateWithLifecycle()
  val settings by vm.app.settings.values.collectAsStateWithLifecycle()
  val (label, ok) =
    when (settings.videoSource) {
      VideoSource.PHONE_CAMERA -> "Video: phone camera" to true
      VideoSource.SIMULATED_GLASSES -> "Video: simulated glasses (demo)" to true
      VideoSource.GLASSES ->
        when {
          !gs.isRegistered -> "Glasses not connected. Open Settings." to false
          gs.deviceCount == 0 -> "Glasses connected to app, not in range" to false
          else -> "${gs.deviceName ?: "Glasses"} ready${gs.battery?.let { ", battery $it%" } ?: ""}" to true
        }
    }
  Card(Modifier.fillMaxWidth().clickable { vm.push(Screen.Settings) }) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(if (ok) "●" else "●", color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828))
      Spacer(Modifier.width(8.dp))
      Text(label)
    }
  }
}

private fun timeOf(iso: String?): String =
  iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a")) }.getOrNull() } ?: ""

@Composable
fun JobCard(job: Job, onClick: () -> Unit) {
  Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row {
        Text(job.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(timeOf(job.scheduled_at), style = MaterialTheme.typography.labelMedium)
      }
      Text("${job.trade.uppercase()} · ${job.customer_name ?: "No customer"}", style = MaterialTheme.typography.labelMedium)
      job.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      job.last_visit?.let { Text("Last visit: ${it.state}${it.report_status?.let { r -> ", report $r" } ?: ""}", style = MaterialTheme.typography.labelSmall) }
    }
  }
}

// ---- Job detail ------------------------------------------------------------------------------

@Composable
fun JobDetailScreen(vm: AppViewModel, job: Job, onStart: (Job) -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val ctx = LocalContext.current
  FpScaffold(job.title, onBack = { vm.back() }) { mod ->
    Column(mod.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(job.trade.uppercase(), style = MaterialTheme.typography.labelLarge)
      job.complaint?.let { LabeledBlock("Complaint", it) }
      job.customer_name?.let { LabeledBlock("Customer", listOfNotNull(it, job.customer_phone).joinToString("  ")) }
      job.address?.let { addr ->
        LabeledBlock("Address", addr)
        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(addr)))) }) { Text("Open in Maps") }
      }
      job.access_notes?.let { LabeledBlock("Access notes", it) }
      job.recording_pref?.let { LabeledBlock("Customer recording preference", it) }
      val trade = state.trades.firstOrNull { it.id == job.trade }?.profile
      trade?.preWorkReadings?.takeIf { it.isNotEmpty() }?.let { r -> LabeledBlock("Safety readings before work", r.joinToString("\n") { "• ${it.label}${it.unit?.let { u -> " ($u)" } ?: ""}" }) }
      trade?.stopRules?.takeIf { it.isNotEmpty() }?.let { LabeledBlock("Stop and call the expert if", it.joinToString("\n") { s -> "• $s" }) }
      HorizontalDivider()
      Button(onClick = { onStart(job) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("startVisit")) {
        if (state.busy) CircularProgressIndicator(Modifier.width(22.dp)) else Text(if (vm.visit != null) "Return to visit" else "Start visit")
      }
      job.last_visit?.takeIf { it.ended_at != null }?.let { lv ->
        OutlinedButton(onClick = { vm.push(Screen.Report(lv.id)) }, modifier = Modifier.fillMaxWidth()) { Text("Review last report") }
      }
      ErrorText(state.error)
    }
  }
}

@Composable
fun LabeledBlock(label: String, text: String) {
  Column {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text(text, style = MaterialTheme.typography.bodyLarge)
  }
}

// ---- Walk-in ---------------------------------------------------------------------------------

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
  FpScaffold("Walk-in job", onBack = { vm.back() }) { mod ->
    Column(mod.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
          value = trades?.firstOrNull { it.id == trade }?.name ?: trade,
          onValueChange = {},
          readOnly = true,
          label = { Text("Trade") },
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
          modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
          (trades?.map { it.id to it.name } ?: listOf("hvac" to "HVAC", "electrical" to "Electrical", "plumbing" to "Plumbing", "roofing" to "Roofing", "cleaning" to "Cleaning", "auto" to "Auto", "appliance" to "Appliance", "general" to "General")).forEach { (id, n) ->
            DropdownMenuItem(text = { Text(n) }, onClick = {
              trade = id
              open = false
            })
          }
        }
      }
      OutlinedTextField(title, { title = it }, label = { Text("Job title, e.g. No heat") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(complaint, { complaint = it }, label = { Text("What the customer says is wrong") }, minLines = 2, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(address, { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(name, { name = it }, label = { Text("Customer name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(phone, { phone = it }, label = { Text("Customer phone") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
      Button(onClick = { vm.createWalkIn(trade, title.trim(), complaint.trim(), address.trim(), name, phone) }, enabled = !state.busy && title.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text("Create job")
      }
      ErrorText(state.error)
    }
  }
}

// ---- Settings --------------------------------------------------------------------------------

@Composable
fun SettingsScreen(vm: AppViewModel, activity: MainActivity) {
  val s by vm.app.settings.values.collectAsStateWithLifecycle()
  val gs by vm.app.glasses.status.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  var server by remember { mutableStateOf(s.apiBaseUrl) }
  var wake by remember { mutableStateOf(s.wakePhraseOverride) }
  var message by remember { mutableStateOf<String?>(null) }
  val ctx = LocalContext.current
  activity.glassesPermissionMessage = { message = it }
  val pickVideo =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        vm.app.settings.update { it.copy(simulatedVideoUri = uri.toString()) }
        message = "Demo video set. It must be H.265 (HEVC)."
      }
    }
  FpScaffold("Settings", onBack = { vm.back() }) { mod ->
    Column(mod.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Video source", style = MaterialTheme.typography.titleMedium)
      VideoSource.entries.forEach { src ->
        Row(Modifier.fillMaxWidth().clickable { vm.app.settings.update { it.copy(videoSource = src) } }, verticalAlignment = Alignment.CenterVertically) {
          RadioButton(selected = s.videoSource == src, onClick = { vm.app.settings.update { it.copy(videoSource = src) } })
          Text(src.label)
        }
      }
      if (s.videoSource == VideoSource.SIMULATED_GLASSES) {
        Text(if (s.simulatedVideoUri.isBlank()) "Using the built-in demo video (furnace control board)." else "Using your video: ${s.simulatedVideoUri.takeLast(40)}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { pickVideo.launch(arrayOf("video/*")) }) { Text("Pick a video") }
          if (s.simulatedVideoUri.isNotBlank()) OutlinedButton(onClick = { vm.app.settings.update { it.copy(simulatedVideoUri = "") } }) { Text("Use built-in") }
        }
      }
      if (s.videoSource == VideoSource.GLASSES) {
        Text("Meta glasses", style = MaterialTheme.typography.titleMedium)
        Text("Toolkit: ${gs.registration.lowercase()} · devices: ${gs.deviceCount}${gs.deviceName?.let { " · $it" } ?: ""}${gs.battery?.let { " · $it%" } ?: ""}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { vm.app.glasses.register(activity) }) { Text(if (gs.isRegistered) "Reconnect glasses" else "Connect Meta glasses") }
          OutlinedButton(onClick = { activity.requestGlassesCamera() }, enabled = gs.isRegistered) { Text("Allow glasses camera") }
        }
        Text("Needs the Meta AI app with Developer Mode on (Settings > App info > tap the version 5 times).", style = MaterialTheme.typography.bodySmall)
        gs.lastError?.let { ErrorText(it) }
      }
      HorizontalDivider()
      OutlinedTextField(server, { server = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(wake, { wake = it }, label = { Text("Wake phrase (blank = company default)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      Button(onClick = {
        vm.app.setBaseUrl(server)
        vm.app.settings.update { it.copy(wakePhraseOverride = wake) }
        message = "Saved."
      }) { Text("Save") }
      Text("AI voice volume: ${(s.aiVoiceVolume * 100).toInt()}%")
      Slider(value = s.aiVoiceVolume, onValueChange = { v -> vm.app.settings.update { it.copy(aiVoiceVolume = v) } })
      Text("Language", style = MaterialTheme.typography.titleSmall)
      Row {
        listOf("en-US" to "English", "es-US" to "Español").forEach { (code, label) ->
          Row(Modifier.clickable { vm.app.settings.update { it.copy(language = code) } }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = s.language == code, onClick = { vm.app.settings.update { it.copy(language = code) } })
            Text(label)
            Spacer(Modifier.width(12.dp))
          }
        }
      }
      SwitchRow("Show transcript during visits", s.showTranscript) { v -> vm.app.settings.update { it.copy(showTranscript = v) } }
      SwitchRow("Volume buttons act as the clip button", s.volumeKeysAsClip) { v -> vm.app.settings.update { it.copy(volumeKeysAsClip = v) } }
      SwitchRow("Upload video on cellular", s.uploadVideoOnCellular) { v -> vm.app.settings.update { it.copy(uploadVideoOnCellular = v) } }
      message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
      HorizontalDivider()
      OutlinedButton(onClick = { scope.launch { vm.logout() } }, enabled = vm.visit == null) { Text("Sign out") }
      Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.labelSmall)
    }
  }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = onChange)
  }
}
