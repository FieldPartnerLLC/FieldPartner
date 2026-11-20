package com.fieldpartner.app.core

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

sealed class ApiException(message: String) : Exception(message) {
  class NotSignedIn : ApiException("Sign in again.")
  class Server(val status: Int, val code: String, message: String) : ApiException(message)
  class Offline : ApiException("No connection to the server. Check signal or Wi-Fi.")
  class Timeout : ApiException("The server took too long to answer. Try again.")
  class BadResponse(detail: String = "") : ApiException("Unexpected reply from the server.$detail")

  /** Worth retrying automatically (network trouble or a server hiccup), as opposed to a real "no". */
  val isTransient: Boolean
    get() =
      when (this) {
        is Offline, is Timeout -> true
        is Server -> status >= 500 || status == 429
        else -> false
      }
}

/**
 * Client for the FieldPartner backend. Every call has a timeout and transient
 * failures retry with backoff, so a weak cell signal turns into a short delay
 * instead of an error on the technician's screen.
 */
class ApiClient(
  @Volatile var baseUrl: String,
  @Volatile var token: String?,
  private val http: OkHttpClient = defaultHttp(),
  private val retryDelayMs: Long = 600,
) {
  companion object {
    private val JSON = "application/json".toMediaType()

    fun defaultHttp(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
  }

  private val uploadHttp: OkHttpClient by lazy {
    http.newBuilder().callTimeout(15, TimeUnit.MINUTES).writeTimeout(5, TimeUnit.MINUTES).readTimeout(2, TimeUnit.MINUTES).build()
  }

  suspend fun <T> request(
    method: String,
    path: String,
    resSer: KSerializer<T>,
    bodyJson: String? = null,
    query: Map<String, String> = emptyMap(),
    retries: Int = 2,
    authenticated: Boolean = true,
  ): T {
    var attempt = 0
    var wait = retryDelayMs
    while (true) {
      try {
        return send(method, path, resSer, bodyJson, query, authenticated)
      } catch (e: ApiException) {
        if (!e.isTransient || attempt >= retries) throw e
        attempt++
        delay(wait)
        wait *= 2
      }
    }
  }

  private suspend fun <T> send(method: String, path: String, resSer: KSerializer<T>, bodyJson: String?, query: Map<String, String>, authenticated: Boolean): T {
    val base = baseUrl.trimEnd('/').toHttpUrlOrNull() ?: throw ApiException.Server(0, "bad_url", "Server address is not valid. Fix it in Settings.")
    val url = base.newBuilder().addPathSegments(path.trimStart('/')).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
    val builder = Request.Builder().url(url).header("Accept", "application/json")
    if (authenticated) {
      val t = token ?: throw ApiException.NotSignedIn()
      builder.header("Authorization", "Bearer $t")
    }
    val body =
      when {
        bodyJson != null -> bodyJson.toRequestBody(JSON)
        method == "POST" || method == "PUT" || method == "PATCH" -> "{}".toRequestBody(JSON)
        else -> null
      }
    builder.method(method, body)
    val response = execute(http.newCall(builder.build()))
    response.use { r ->
      val text = withContext(Dispatchers.IO) { r.body?.string() ?: "" }
      // 401 on a signed-in call means the session expired. On the login call it is a wrong password: show the server's message.
      if (r.code == 401 && authenticated) throw ApiException.NotSignedIn()
      if (r.code >= 400) {
        val err = runCatching { FpJson.decodeFromString(ApiErrorBody.serializer(), text) }.getOrNull()
        throw ApiException.Server(r.code, err?.error ?: "error", err?.message ?: "Server error ${r.code}.")
      }
      return try {
        FpJson.decodeFromString(resSer, text.ifEmpty { "{}" })
      } catch (e: Exception) {
        throw ApiException.BadResponse(" (${path}: ${e.message?.take(120)})")
      }
    }
  }

  private suspend fun execute(call: Call): Response =
    suspendCancellableCoroutine { cont ->
      cont.invokeOnCancellation { call.cancel() }
      call.enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            val mapped =
              when (e) {
                is SocketTimeoutException -> ApiException.Timeout()
                is InterruptedIOException -> if (e.message?.contains("timeout", true) == true) ApiException.Timeout() else ApiException.Offline()
                else -> ApiException.Offline()
              }
            if (cont.isActive) cont.resumeWith(Result.failure(mapped))
          }

          override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resumeWith(Result.success(response)) else response.close()
          }
        }
      )
    }

  private inline fun <reified B> enc(body: B): String = FpJson.encodeToString(serializer<B>(), body)

  /** Upload a file to a signed URL (S3 or the backend's local storage). */
  suspend fun upload(file: File, url: String, contentType: String) {
    val req = Request.Builder().url(url).put(file.asRequestBody(contentType.toMediaType())).build()
    val r = execute(uploadHttp.newCall(req))
    r.use { if (!it.isSuccessful) throw ApiException.Server(it.code, "upload", "Upload failed (${it.code}).") }
  }

  // ---- Endpoints ---------------------------------------------------------------------------

  @Serializable private data class LoginBody(val email: String, val password: String)

  suspend fun login(email: String, password: String): LoginResponse =
    request("POST", "/auth/login", LoginResponse.serializer(), enc(LoginBody(email, password)), authenticated = false)

  suspend fun me(): MeResponse = request("GET", "/me", MeResponse.serializer())

  suspend fun jobs(date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<Job> =
    request("GET", "/jobs", JobsResponse.serializer(), query = mapOf("date" to date.toString(), "tz" to zone.id)).jobs

  @Serializable private data class CustomerBody(val name: String, val phone: String? = null)

  @Serializable
  private data class CreateJobBody(val trade: String, val title: String, val complaint: String, val address: String, val customer: CustomerBody? = null)

  suspend fun createJob(trade: String, title: String, complaint: String, address: String, customerName: String, customerPhone: String): Job {
    val customer = if (customerName.isBlank()) null else CustomerBody(customerName.trim(), customerPhone.trim().ifEmpty { null })
    return request("POST", "/jobs", JobResponse.serializer(), enc(CreateJobBody(trade, title, complaint, address, customer))).job
  }

  suspend fun startVisit(jobId: String): StartVisitResponse = request("POST", "/jobs/$jobId/visits", StartVisitResponse.serializer(), retries = 4)

  suspend fun liveTicket(visitId: String): LiveTicket = request("POST", "/visits/$visitId/live", LiveTicket.serializer(), retries = 3)

  suspend fun consent(visitId: String, body: ConsentBody): ConsentResponse =
    request("POST", "/visits/$visitId/consent", ConsentResponse.serializer(), enc(body), retries = 4)

  @Serializable private data class EventsBody(val events: List<VisitEvent>)

  @Serializable data class EventsResult(val stored: Int = 0, val duplicates: Int = 0)

  suspend fun postEvents(visitId: String, events: List<VisitEvent>): EventsResult =
    request("POST", "/visits/$visitId/events", EventsResult.serializer(), enc(EventsBody(events)), retries = 0)

  @Serializable private data class ReasonBody(val reason: String)

  suspend fun escalate(visitId: String, reason: String): EscalationResponse =
    request("POST", "/visits/$visitId/escalate", EscalationResponse.serializer(), enc(ReasonBody(reason.take(500))), retries = 3)

  suspend fun endEscalation(visitId: String) {
    request("POST", "/visits/$visitId/escalation/end", JsonObject.serializer())
  }

  @Serializable private data class UploadBody(val kind: String, val filename: String, val contentType: String, val bytes: Long, val clientId: String)

  suspend fun requestUpload(visitId: String, kind: String, filename: String, contentType: String, bytes: Long, clientId: String): UploadTicket =
    request("POST", "/visits/$visitId/uploads", UploadTicket.serializer(), enc(UploadBody(kind, filename, contentType, bytes, clientId)))

  @Serializable private data class BytesBody(val bytes: Long)

  suspend fun completeUpload(mediaId: String, bytes: Long) {
    request("POST", "/media/$mediaId/complete", JsonObject.serializer(), enc(BytesBody(bytes)))
  }

  suspend fun endVisit(visitId: String): Visit = request("POST", "/visits/$visitId/end", VisitResponse.serializer(), retries = 4).visit

  suspend fun report(visitId: String): ReportResponse = request("GET", "/visits/$visitId/report", ReportResponse.serializer())

  @Serializable private data class ApproveBody(val report: ServiceReport, val sendSms: Boolean, val sendEmail: Boolean)

  suspend fun approveReport(visitId: String, report: ServiceReport, sendSms: Boolean, sendEmail: Boolean): ApproveResponse =
    request("POST", "/visits/$visitId/report/approve", ApproveResponse.serializer(), enc(ApproveBody(report, sendSms, sendEmail)))
}
