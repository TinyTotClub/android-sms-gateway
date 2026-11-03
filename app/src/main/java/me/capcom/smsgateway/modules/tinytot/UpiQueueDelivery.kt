package me.capcom.smsgateway.modules.tinytot

import com.google.gson.Gson
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.tinytot.api.UpiApiException
import me.capcom.smsgateway.modules.tinytot.api.UpiVerifierClient
import me.capcom.smsgateway.modules.webhooks.WebhookPayloadStorage
import me.capcom.smsgateway.modules.webhooks.db.WebhookQueueEntity

/**
 * Sends queued PNB SMS (webhook-queue entries with [QUEUE_URL]) to the verifier as
 * `ingest_sms` batches. The queue worker applies the returned outcomes: complete,
 * retry with backoff, or fail permanently.
 */
class UpiQueueDelivery(
    private val client: UpiVerifierClient,
    private val payloadStorage: WebhookPayloadStorage,
    private val notifier: UpiNotifier,
    private val logsService: LogsService,
) {
    sealed class Outcome {
        object Delivered : Outcome()
        class Retry(val error: String) : Outcome()
        class Drop(val error: String) : Outcome()
    }

    private val gson = Gson()

    suspend fun deliver(entries: List<WebhookQueueEntity>): Map<String, Outcome> {
        val outcomes = mutableMapOf<String, Outcome>()

        val readable = entries.mapNotNull { entry ->
            val sms = payloadStorage.read(entry.payload)?.let {
                runCatching { gson.fromJson(it, UpiVerifierClient.Sms::class.java) }.getOrNull()
            }?.takeIf { !it.sender.isNullOrEmpty() && !it.body.isNullOrEmpty() }

            if (sms == null) {
                outcomes[entry.id] = Outcome.Drop("Unreadable payload")
                null
            } else {
                entry to sms
            }
        }

        readable.chunked(UpiVerifierClient.MAX_BATCH).forEach { chunk ->
            outcomes += deliverChunk(chunk)
        }

        return outcomes
    }

    private suspend fun deliverChunk(
        chunk: List<Pair<WebhookQueueEntity, UpiVerifierClient.Sms>>,
    ): Map<String, Outcome> {
        val results = try {
            client.ingestSms(chunk.map { it.second })
        } catch (e: UpiApiException) {
            log(
                LogEntry.Priority.WARN,
                "ingest_sms failed: ${e.code} ${e.message}",
                mapOf("count" to chunk.size, "httpStatus" to e.httpStatus),
            )
            val outcome = if (e.isPermanent) {
                Outcome.Drop("${e.code}: ${e.message}")
            } else {
                Outcome.Retry("${e.code}: ${e.message}")
            }
            return chunk.associate { it.first.id to outcome }
        }

        val byIndex = results.associateBy { it.index }
        return chunk.mapIndexed { index, (entry, sms) ->
            val result = byIndex[index]
            val outcome = when {
                result == null -> Outcome.Retry("No result for message")
                result.isFinal -> Outcome.Delivered
                else -> Outcome.Retry("Unexpected status ${result.status}")
            }

            if (result != null && result.status == "stored") {
                notifyResult(result.alertId ?: entry.id, result.matchStatus, result.intentStatus, sms)
            }
            if (result?.status == "invalid" || result?.status == "ignored_sender") {
                log(
                    LogEntry.Priority.WARN,
                    "Verifier did not store a forwarded SMS: ${result.status}",
                    mapOf("sender" to sms.sender),
                )
            }

            entry.id to outcome
        }.toMap()
    }

    private fun notifyResult(
        key: String,
        matchStatus: String?,
        intentStatus: String?,
        sms: UpiVerifierClient.Sms,
    ) {
        val amount = UpiAmounts.extractDisplayAmount(sms.body)
        when {
            matchStatus == "matched" && intentStatus == "verified" ->
                notifier.paymentVerified(key, amount)

            matchStatus == "matched" || matchStatus == "ambiguous" || matchStatus == "unmatched" ->
                notifier.creditNeedsReview(key, amount)
        }
    }

    private fun log(priority: LogEntry.Priority, message: String, context: Map<String, Any?>) {
        logsService.insert(priority, MODULE_NAME, message, context)
    }

    companion object {
        /** Marker URL for queue entries owned by the TinyTot UPI verifier. */
        const val QUEUE_URL = "tinytot-upi://ingest_sms"

        fun owns(url: String): Boolean = url == QUEUE_URL
    }
}
