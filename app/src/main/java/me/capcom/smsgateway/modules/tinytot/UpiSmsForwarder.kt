package me.capcom.smsgateway.modules.tinytot

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.modules.incoming.db.IncomingMessageType
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.receiver.ReceiverService
import me.capcom.smsgateway.modules.receiver.data.InboxMessage
import me.capcom.smsgateway.modules.tinytot.api.UpiVerifierClient
import me.capcom.smsgateway.modules.webhooks.WebhooksSettings
import me.capcom.smsgateway.modules.webhooks.db.WebhookQueueRepository
import me.capcom.smsgateway.modules.webhooks.workers.WebhookQueueProcessorWorker
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Date

/**
 * Puts PNB bank SMS on the app's persistent webhook queue. Entries carry the
 * [UpiQueueDelivery.QUEUE_URL] marker; the queue worker hands them to
 * [UpiQueueDelivery], which sends them as signed `ingest_sms` batches with the queue's
 * retry/backoff. Non-PNB SMS are dropped here and never leave the phone.
 */
class UpiSmsForwarder(
    private val settings: TinyTotSettings,
    private val queueRepository: WebhookQueueRepository,
    private val webhooksSettings: WebhooksSettings,
    private val logsService: LogsService,
) : KoinComponent {
    private val gson = Gson()

    // Lazy: ReceiverService itself calls this forwarder.
    private val receiverService: ReceiverService by inject()

    /** Called for every newly received text SMS. Returns true if it was queued. */
    fun onIncomingSms(context: Context, sender: String, body: String, receivedAt: Long): Boolean {
        if (!PnbSenderFilter.isPnbSender(sender)) return false

        if (!settings.forwardEnabled) {
            log(LogEntry.Priority.INFO, "PNB SMS not forwarded: forwarding is off")
            return false
        }
        if (!settings.isConfigured) {
            log(LogEntry.Priority.WARN, "PNB SMS not forwarded: verifier URL or API key is not set")
            return false
        }

        return try {
            runBlocking { enqueue(UpiVerifierClient.Sms(sender, body, receivedAt)) }
            WebhookQueueProcessorWorker.start(context, webhooksSettings.internetRequired)
            true
        } catch (e: Exception) {
            log(
                LogEntry.Priority.ERROR,
                "Failed to queue PNB SMS: ${e.message}",
                mapOf("error" to e.toString()),
            )
            false
        }
    }

    /**
     * Re-reads PNB SMS from the inbox and queues them again. The server dedupes by
     * body hash, so already-delivered messages come back as `duplicate`.
     */
    suspend fun resendRecent(context: Context, hours: Int = 24): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val period = Date(now - hours * 3_600_000L) to Date(now)

        val messages = receiverService.select(context, period, setOf(IncomingMessageType.SMS))
            .filterIsInstance<InboxMessage.Text>()
            .filter { PnbSenderFilter.isPnbSender(it.address) }

        messages.forEach {
            enqueue(UpiVerifierClient.Sms(it.address, it.text, it.date.time))
        }
        if (messages.isNotEmpty()) {
            WebhookQueueProcessorWorker.start(context, webhooksSettings.internetRequired)
        }

        log(LogEntry.Priority.INFO, "Re-queued ${messages.size} PNB SMS from the last ${hours}h")
        messages.size
    }

    private suspend fun enqueue(sms: UpiVerifierClient.Sms) {
        val id = queueRepository.enqueueWebhook(UpiQueueDelivery.QUEUE_URL, gson.toJson(sms))
        log(
            LogEntry.Priority.DEBUG,
            "PNB SMS queued for the UPI verifier",
            mapOf("queueId" to id, "sender" to sms.sender),
        )
    }

    private fun log(priority: LogEntry.Priority, message: String, context: Map<String, Any?> = emptyMap()) {
        logsService.insert(priority, MODULE_NAME, message, context)
    }
}
