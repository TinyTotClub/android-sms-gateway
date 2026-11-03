package me.capcom.smsgateway.modules.tinytot.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.BuildConfig
import me.capcom.smsgateway.modules.tinytot.TinyTotSettings
import me.capcom.smsgateway.modules.tinytot.UpiSigner
import me.capcom.smsgateway.modules.tinytot.UpiTime
import me.capcom.smsgateway.modules.tinytot.domain.IngestResult
import me.capcom.smsgateway.modules.tinytot.domain.OpenItems
import me.capcom.smsgateway.modules.tinytot.domain.PingResult
import me.capcom.smsgateway.modules.tinytot.domain.objects
import me.capcom.smsgateway.modules.tinytot.domain.str
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Signed client for the `upi-verifier` Edge Function (see docs/tinytot-upi-verifier.md). */
class UpiVerifierClient(
    private val settings: TinyTotSettings,
) {
    data class Sms(val sender: String, val body: String, val receivedAt: Long)

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun ping(): PingResult {
        val json = call("ping")
        return PingResult(
            ok = json.get("ok")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            serverTime = UpiTime.parseMillis(json.str("serverTime")),
            schoolId = json.str("schoolId"),
        )
    }

    suspend fun ingestSms(messages: List<Sms>): List<IngestResult> {
        require(messages.size in 1..MAX_BATCH) { "1..$MAX_BATCH messages per request" }
        val array = JsonArray().apply {
            messages.forEach {
                add(JsonObject().apply {
                    addProperty("sender", it.sender)
                    addProperty("body", it.body)
                    addProperty("receivedAt", it.receivedAt)
                })
            }
        }
        val json = call("ingest_sms") { add("messages", array) }
        return json.objects("results").mapNotNull { IngestResult.fromJson(it) }
    }

    suspend fun listOpen(sinceHours: Int = 72): OpenItems =
        OpenItems.fromJson(call("list_open") { addProperty("sinceHours", sinceHours) })

    suspend fun verifyIntent(
        intentId: String,
        alertId: String?,
        utr: String?,
        amountReceived: String?,
        note: String? = null,
    ): JsonObject = call("verify_intent") {
        addProperty("intentId", intentId)
        alertId?.let { addProperty("alertId", it) }
        utr?.takeIf { it.isNotBlank() }?.let { addProperty("utr", it.trim()) }
        amountReceived?.let { addProperty("amountReceived", it) }
        note?.takeIf { it.isNotBlank() }?.let { addProperty("note", it.trim()) }
    }

    suspend fun rejectIntent(intentId: String, reason: String): JsonObject =
        call("reject_intent") {
            addProperty("intentId", intentId)
            addProperty("reason", reason.trim())
        }

    suspend fun ignoreAlert(alertId: String, reason: String): JsonObject =
        call("ignore_alert") {
            addProperty("alertId", alertId)
            addProperty("reason", reason.trim())
        }

    private suspend fun call(
        action: String,
        params: JsonObject.() -> Unit = {},
    ): JsonObject = withContext(Dispatchers.IO) {
        try {
            execute(action, params).also { settings.markSuccess() }
        } catch (e: UpiApiException) {
            settings.markError(
                when {
                    e.authReason == "stale" -> "unauthenticated: stale (check the phone clock)"
                    e.authReason != null -> "unauthenticated: ${e.authReason}"
                    else -> "${e.code}: ${e.message}"
                }
            )
            throw e
        }
    }

    private fun execute(action: String, params: JsonObject.() -> Unit): JsonObject {
        val url = settings.verifierUrl?.toHttpUrlOrNull()
            ?: throw UpiApiException(UpiApiException.CODE_NOT_CONFIGURED, "Verifier URL is not set")
        val key = settings.apiKey
            ?: throw UpiApiException(UpiApiException.CODE_NOT_CONFIGURED, "API key is not set")

        val body = JsonObject().apply {
            addProperty("action", action)
            params()
        }
        // Serialize once; these exact bytes are signed and sent.
        val raw = gson.toJson(body).toByteArray(Charsets.UTF_8)
        val timestamp = System.currentTimeMillis().toString()

        val request = Request.Builder()
            .url(url)
            .post(raw.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
            .header(UpiSigner.HEADER_TIMESTAMP, timestamp)
            .header(UpiSigner.HEADER_SIGNATURE, UpiSigner.sign(key, timestamp, raw))
            .header(UpiSigner.HEADER_DEVICE, settings.deviceId)
            .build()

        val (status, responseBytes, signatureOk) = try {
            http.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                Triple(
                    response.code,
                    bytes,
                    UpiSigner.verify(
                        key,
                        response.header(UpiSigner.HEADER_TIMESTAMP),
                        response.header(UpiSigner.HEADER_SIGNATURE),
                        bytes,
                    ),
                )
            }
        } catch (e: IOException) {
            throw UpiApiException(
                UpiApiException.CODE_NETWORK,
                e.message ?: e.javaClass.simpleName,
                cause = e,
            )
        }

        val json = runCatching {
            JsonParser.parseString(String(responseBytes, Charsets.UTF_8))
                .takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()

        if (status !in 200..299) {
            // 401/503 replies are unsigned by design; an error is never trusted as success anyway.
            val error = json?.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
            throw UpiApiException(
                code = error?.str("code") ?: "http_$status",
                message = error?.str("message") ?: "HTTP $status",
                httpStatus = status,
            )
        }

        if (!signatureOk) {
            throw UpiApiException(
                UpiApiException.CODE_BAD_RESPONSE_SIGNATURE,
                "Server response signature is missing or wrong",
                httpStatus = status,
            )
        }

        return json ?: throw UpiApiException(
            UpiApiException.CODE_BAD_RESPONSE,
            "Server response is not a JSON object",
            httpStatus = status,
        )
    }

    companion object {
        const val MAX_BATCH = 50
        private val JSON = "application/json".toMediaType()
    }
}

class UpiApiException(
    val code: String,
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** 401 rejection reason: missing_headers | bad_timestamp | stale | bad_signature | bad_device. */
    val authReason: String?
        get() = if (httpStatus == 401) message else null

    /** Resending the same request cannot succeed (bad request, conflict, ...). */
    val isPermanent: Boolean
        get() = httpStatus == 400

    companion object {
        const val CODE_NOT_CONFIGURED = "not_configured"
        const val CODE_NETWORK = "network"
        const val CODE_BAD_RESPONSE = "bad_response"
        const val CODE_BAD_RESPONSE_SIGNATURE = "bad_response_signature"
    }
}
