package com.fieldpartner.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldpartner.app.core.ServiceReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** After the visit: the AI drafts, the technician corrects and approves. Nothing reaches the customer before approval. */
@Composable
fun ReportScreen(vm: AppViewModel, visitId: String) {
  var report by remember { mutableStateOf<ServiceReport?>(null) }
  var status by remember { mutableStateOf("generating") }
  var sendSms by remember { mutableStateOf(true) }
  var sendEmail by remember { mutableStateOf(true) }
  var busy by remember { mutableStateOf(false) }
  var result by remember { mutableStateOf<String?>(null) }
  var attempt by remember { mutableIntStateOf(0) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(attempt) {
    repeat(60) {
      runCatching { vm.app.api.report(visitId) }.onSuccess { r ->
        status = r.status
        if (r.report != null && report == null) report = r.report
      }
      if (report != null || status == "failed") return@LaunchedEffect
      delay(1_500)
    }
  }

  FpScaffold("Service report", onBack = { vm.back() }) { mod ->
    Column(mod.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      val r = report
      if (r == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (status != "failed") CircularProgressIndicator(Modifier.width(24.dp))
          Text(if (status == "failed") "  Drafting failed." else "  The AI is drafting the report...")
        }
        if (status == "failed") OutlinedButton(onClick = { status = "generating"; attempt++ }) { Text("Try again") }
        return@Column
      }
      Field("Summary", r.summary) { report = r.copy(summary = it) }
      Field("Customer complaint", r.customerComplaint) { report = r.copy(customerComplaint = it) }
      Field("Equipment", r.equipment) { report = r.copy(equipment = it) }
      ListField("Safety readings", r.safetyReadings) { report = r.copy(safetyReadings = it) }
      ListField("Findings", r.findings) { report = r.copy(findings = it) }
      ListField("Work performed", r.workPerformed) { report = r.copy(workPerformed = it) }
      ListField("Parts used", r.partsUsed) { report = r.copy(partsUsed = it) }
      ListField("Readings", r.readings) { report = r.copy(readings = it) }
      ListField("Safety events", r.safetyEvents) { report = r.copy(safetyEvents = it) }
      ListField("Customer decisions", r.customerDecisions) { report = r.copy(customerDecisions = it) }
      ListField("Follow-up", r.followUp) { report = r.copy(followUp = it) }
      SwitchRow("Send by text message", sendSms) { sendSms = it }
      SwitchRow("Send by email with PDF", sendEmail) { sendEmail = it }
      Button(
        onClick = {
          busy = true
          scope.launch {
            result =
              try {
                val a = vm.app.api.approveReport(visitId, report!!.cleaned(), sendSms, sendEmail)
                if (a.delivered.isEmpty()) "Approved. Not sent (no customer phone or email, or messaging not set up). Link: ${a.url}" else "Sent by ${a.delivered.joinToString(" and ")}."
              } catch (e: Exception) {
                e.message
              }
            busy = false
          }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("approve"),
      ) { if (busy) CircularProgressIndicator(Modifier.width(22.dp)) else Text("Approve and send") }
      result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
  }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
  OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), minLines = 1)
}

@Composable
private fun ListField(label: String, items: List<String>, onChange: (List<String>) -> Unit) {
  OutlinedTextField(
    value = items.joinToString("\n"),
    onValueChange = { t -> onChange(t.split("\n")) },
    label = { Text("$label (one per line)") },
    modifier = Modifier.fillMaxWidth(),
    minLines = 2,
  )
}
