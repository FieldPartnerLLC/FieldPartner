package com.fieldpartner.app.core

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Wire models for the FieldPartner backend. Field names follow the server's JSON
// (snake_case database columns, camelCase API objects). Same contract as the iOS app.

val FpJson = Json {
  ignoreUnknownKeys = true
  explicitNulls = false // zod .optional() rejects null, so nulls are left out
  encodeDefaults = true
  coerceInputValues = true
}

@Serializable
data class UserInfo(
  val id: String,
  val name: String,
  val email: String,
  val role: String,
  val trades: List<String>? = null,
  val certifications: List<String>? = null,
  val companyId: String? = null,
)

@Serializable
data class CompanyInfo(
  val id: String,
  val name: String,
  val phone: String? = null,
  val wake_phrase: String = "hey dispatch",
  val co_stop_ppm: Int = 35,
  val retention_days: Int? = null,
  val diagnostic_fee_cents: Int? = null,
  val license_numbers: String? = null,
  val address: String? = null,
)

@Serializable data class LoginResponse(val token: String, val user: UserInfo, val company: CompanyInfo? = null)

@Serializable
data class MeResponse(val user: UserInfo, val company: CompanyInfo? = null, val trades: List<TradeProfileRow> = emptyList())

@Serializable data class TradeProfileRow(val id: String, val name: String, val profile: TradeProfile)

@Serializable
data class TradeProfile(
  val id: String? = null,
  val name: String? = null,
  val description: String? = null,
  val preWorkReadings: List<PreWork>? = null,
  val stopRules: List<String>? = null,
  val neverGuide: List<String>? = null,
  val verification: List<String>? = null,
) {
  @Serializable data class PreWork(val key: String, val label: String, val unit: String? = null, val requiredWhen: String? = null)
}

@Serializable data class LastVisit(val id: String, val state: String, val ended_at: String? = null, val report_status: String? = null)

@Serializable
data class Job(
  val id: String,
  val company_id: String,
  val trade: String,
  val title: String,
  val complaint: String? = null,
  val address: String? = null,
  val scheduled_at: String? = null,
  val status: String,
  val assigned_tech_id: String? = null,
  val access_notes: String? = null,
  val recording_pref: String? = null,
  val customer_name: String? = null,
  val customer_phone: String? = null,
  val customer_email: String? = null,
  val tech_name: String? = null,
  val last_visit: LastVisit? = null,
)

@Serializable data class JobsResponse(val jobs: List<Job>)

@Serializable data class JobResponse(val job: Job)

@Serializable
data class Visit(
  val id: String,
  val job_id: String,
  val company_id: String,
  val tech_id: String,
  val room_name: String,
  val state: String,
  val consent_choice: String? = null,
  val private_areas_ok: Boolean? = null,
  val started_at: String,
  val ended_at: String? = null,
  val escalation_status: String? = null,
  val report_status: String? = null,
)

@Serializable
data class StartVisitResponse(val visit: Visit, val resumed: Boolean, val live: Live) {
  @Serializable data class Live(val available: Boolean)
}

@Serializable data class VisitResponse(val visit: Visit)

@Serializable data class LiveTicket(val url: String, val room: String, val token: String, val identity: String)

@Serializable data class ConsentResponse(val consentId: String, val recording: String, val cloudRecording: Boolean)

@Serializable
data class ConsentBody(
  val kind: String,
  val choice: String? = null,
  val privateAreasOk: Boolean? = null,
  val signerName: String? = null,
  val signerRole: String? = null,
  val language: String = "en",
  val lat: Double? = null,
  val lng: Double? = null,
  val textVersion: String = ConsentText.VERSION,
  val signaturePngBase64: String? = null,
)

@Serializable data class EscalationResponse(val status: String, val paged: String? = null)

@Serializable
data class UploadTicket(val mediaId: String, val url: String? = null, val key: String? = null, val alreadyUploaded: Boolean? = null)

@Serializable
data class ServiceReport(
  val customerComplaint: String = "",
  val equipment: String = "",
  val safetyReadings: List<String> = emptyList(),
  val readings: List<String> = emptyList(),
  val findings: List<String> = emptyList(),
  val workPerformed: List<String> = emptyList(),
  val partsUsed: List<String> = emptyList(),
  val observations: List<String> = emptyList(),
  val safetyEvents: List<String> = emptyList(),
  val supervisorInvolvement: String = "",
  val customerDecisions: List<String> = emptyList(),
  val followUp: List<String> = emptyList(),
  val summary: String = "",
) {
  /** Trims text and drops the blank lines left behind by editing in a text box. */
  fun cleaned(): ServiceReport {
    fun l(x: List<String>) = x.map { it.trim() }.filter { it.isNotEmpty() }
    return copy(
      customerComplaint = customerComplaint.trim(),
      equipment = equipment.trim(),
      safetyReadings = l(safetyReadings),
      readings = l(readings),
      findings = l(findings),
      workPerformed = l(workPerformed),
      partsUsed = l(partsUsed),
      observations = l(observations),
      safetyEvents = l(safetyEvents),
      supervisorInvolvement = supervisorInvolvement.trim(),
      customerDecisions = l(customerDecisions),
      followUp = l(followUp),
      summary = summary.trim(),
    )
  }
}

@Serializable data class ReportResponse(val status: String, val report: ServiceReport? = null, val token: String? = null)

@Serializable data class ApproveResponse(val token: String, val url: String, val delivered: List<String> = emptyList())

@Serializable data class ApiErrorBody(val error: String? = null, val message: String? = null)

/** A visit event, created on the phone and delivered to the server at least once. */
@Serializable
data class VisitEvent(
  val id: String,
  val ts: String,
  val type: String,
  val actor: String = "tech",
  val data: JsonObject = JsonObject(emptyMap()),
) {
  companion object {
    fun create(type: String, data: Map<String, Any?> = emptyMap(), actor: String = "tech", at: Instant = Instant.now()) =
      VisitEvent(id = "and-${UUID.randomUUID()}", ts = at.toString(), type = type, actor = actor, data = jsonObjectOf(data))
  }
}

/** Builds a JsonObject from plain Kotlin values (String, Number, Boolean, null, Map, List). */
fun jsonObjectOf(map: Map<String, Any?>): JsonObject = JsonObject(map.mapValues { toJson(it.value) })

fun toJson(v: Any?): JsonElement =
  when (v) {
    null -> kotlinx.serialization.json.JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
    is Iterable<*> -> kotlinx.serialization.json.JsonArray(v.map { toJson(it) })
    else -> JsonPrimitive(v.toString())
  }
