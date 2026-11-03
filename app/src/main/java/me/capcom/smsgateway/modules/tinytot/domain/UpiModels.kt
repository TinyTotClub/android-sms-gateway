package me.capcom.smsgateway.modules.tinytot.domain

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import me.capcom.smsgateway.modules.tinytot.UpiAmounts
import me.capcom.smsgateway.modules.tinytot.UpiTime
import java.math.BigDecimal

/** An open `upi_payment_intents` row as returned by `list_open`. */
data class UpiIntent(
    val id: String,
    val reference: String?,
    val description: String?,
    val studentId: String?,
    val billingId: String?,
    val amountExpected: BigDecimal?,
    val amountTolerance: BigDecimal?,
    val status: Status,
    val claimUtr: String?,
    val claimAmount: BigDecimal?,
    val createdAt: Long?,
    val claimedAt: Long?,
    val expiresAt: Long?,
    val billingDescription: String?,
) {
    enum class Status(val wire: String) {
        Pending("pending"),
        Claimed("claimed"),
        Mismatch("mismatch"),
        Failed("failed"),
        Other("");

        companion object {
            fun from(value: String?): Status =
                values().firstOrNull { it.wire.isNotEmpty() && it.wire == value } ?: Other
        }
    }

    val title: String?
        get() = description?.takeIf { it.isNotBlank() } ?: billingDescription

    companion object {
        fun fromJson(json: JsonObject): UpiIntent? {
            val id = json.str("id") ?: return null
            val billing = json.get("billing")?.takeIf { it.isJsonObject }?.asJsonObject
            return UpiIntent(
                id = id,
                reference = json.str("reference"),
                description = json.str("description"),
                studentId = json.str("student_id") ?: billing?.str("student_id"),
                billingId = json.str("billing_id") ?: billing?.str("id"),
                amountExpected = UpiAmounts.parse(json.get("amount_expected")),
                amountTolerance = UpiAmounts.parse(json.get("amount_tolerance")),
                status = Status.from(json.str("status")),
                claimUtr = json.str("claim_utr"),
                claimAmount = UpiAmounts.parse(json.get("claim_amount")),
                createdAt = UpiTime.parseMillis(json.str("created_at")),
                claimedAt = UpiTime.parseMillis(json.str("claimed_at")),
                expiresAt = UpiTime.parseMillis(json.str("expires_at")),
                billingDescription = billing?.str("description") ?: billing?.str("type"),
            )
        }
    }
}

/** An unmatched or ambiguous bank credit (`upi_credit_alerts` row). */
data class UpiAlert(
    val id: String,
    val sender: String?,
    val receivedAt: Long?,
    val amount: BigDecimal?,
    val utr: String?,
    val payerName: String?,
    val accountLast4: String?,
    val matchStatus: String?,
    val candidateIntentIds: List<String>,
) {
    val isAmbiguous: Boolean get() = matchStatus == "ambiguous"

    companion object {
        fun fromJson(json: JsonObject): UpiAlert? {
            val id = json.str("id") ?: return null
            return UpiAlert(
                id = id,
                sender = json.str("sender"),
                receivedAt = UpiTime.parseMillis(json.str("received_at")),
                amount = UpiAmounts.parse(json.get("amount")),
                utr = json.str("utr"),
                payerName = json.str("payer_name"),
                accountLast4 = json.str("account_last4"),
                matchStatus = json.str("match_status"),
                candidateIntentIds = (json.get("candidate_intent_ids") as? JsonArray)
                    ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                    ?: emptyList(),
            )
        }
    }
}

data class OpenItems(
    val intents: List<UpiIntent>,
    val alerts: List<UpiAlert>,
) {
    companion object {
        fun fromJson(json: JsonObject): OpenItems = OpenItems(
            intents = json.objects("intents").mapNotNull { UpiIntent.fromJson(it) },
            alerts = json.objects("alerts").mapNotNull { UpiAlert.fromJson(it) },
        )
    }
}

data class PingResult(
    val ok: Boolean,
    val serverTime: Long?,
    val schoolId: String?,
)

/** One entry of the `ingest_sms` response. */
data class IngestResult(
    val index: Int,
    val status: String,
    val alertId: String?,
    val matchStatus: String?,
    val intentId: String?,
    val intentStatus: String?,
) {
    /** The server has the message (or deliberately refused it); never resend. */
    val isFinal: Boolean
        get() = status in setOf("stored", "duplicate", "ignored_sender", "invalid")

    companion object {
        fun fromJson(json: JsonObject): IngestResult? {
            val index = json.get("index")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
            return IngestResult(
                index = index,
                status = json.str("status") ?: "",
                alertId = json.str("alertId"),
                matchStatus = json.str("matchStatus"),
                intentId = json.str("intentId"),
                intentStatus = json.str("intentStatus"),
            )
        }
    }
}

internal fun JsonObject.str(key: String): String? {
    val element: JsonElement = get(key) ?: return null
    if (!element.isJsonPrimitive) return null
    return element.asString.takeIf { it.isNotEmpty() }
}

internal fun JsonObject.objects(key: String): List<JsonObject> =
    (get(key) as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
        ?: emptyList()
