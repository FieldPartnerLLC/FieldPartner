package com.fieldpartner.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.ui.text.style.TextAlign
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
    PageColumn(mod) {
      val r = report
      if (r == null) {
        SectionCard {
          if (status == "failed") {
            EmptyState(Icons.Outlined.ErrorOutline, "Drafting failed", "The AI could not draft this report. Try again, or write it on the portal.") {
              PrimaryButton("Try again", onClick = { status = "generating"; attempt++ })
            }
          } else {
            Loading()
            Text("The AI is drafting the report from this visit. This takes about 30 seconds.", style = MaterialTheme.typography.bodyMedium, color = Fp.palette.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
          }
        }
        return@PageColumn
      }
      Banner("Check and correct the draft. Nothing reaches the customer until you approve it.", BannerKind.INFO)
      SectionCard(title = "Overview") {
        Field("Summary", r.summary) { report = r.copy(summary = it) }
        Field("Customer complaint", r.customerComplaint) { report = r.copy(customerComplaint = it) }
        Field("Equipment", r.equipment) { report = r.copy(equipment = it) }
      }
      SectionCard(title = "Work") {
        ListField("Findings", r.findings) { report = r.copy(findings = it) }
        ListField("Work performed", r.workPerformed) { report = r.copy(workPerformed = it) }
        ListField("Parts used", r.partsUsed) { report = r.copy(partsUsed = it) }
        ListField("Readings", r.readings) { report = r.copy(readings = it) }
      }
      SectionCard(title = "Safety and follow-up") {
        ListField("Safety readings", r.safetyReadings) { report = r.copy(safetyReadings = it) }
        ListField("Safety events", r.safetyEvents) { report = r.copy(safetyEvents = it) }
        ListField("Customer decisions", r.customerDecisions) { report = r.copy(customerDecisions = it) }
        ListField("Follow-up", r.followUp) { report = r.copy(followUp = it) }
      }
      SectionCard(title = "Send to the customer", padding = PaddingValues(top = 16.dp, bottom = 4.dp)) {
        SwitchRow("Text message", sendSms, subtitle = "A link to the report", icon = Icons.Outlined.Sms) { sendSms = it }
        RowDivider()
        SwitchRow("Email with PDF", sendEmail, subtitle = "The full report attached", icon = Icons.Outlined.Email) { sendEmail = it }
      }
      PrimaryButton(
        "Approve and send",
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
        icon = Icons.AutoMirrored.Filled.Send,
        loading = busy,
        tall = true,
        modifier = Modifier.testTag("approve"),
      )
      result?.let { Banner(it, if (it.startsWith("Sent") || it.startsWith("Approved")) BannerKind.OK else BannerKind.ERROR) }
    }
  }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
  FpTextField(value, onChange, label, singleLine = false)
}

@Composable
private fun ListField(label: String, items: List<String>, onChange: (List<String>) -> Unit) {
  FpTextField(
    value = items.joinToString("\n"),
    onValueChange = { t -> onChange(t.split("\n")) },
    label = label,
    supportingText = "One per line",
    singleLine = false,
    minLines = 2,
  )
}
