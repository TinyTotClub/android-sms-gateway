package me.capcom.smsgateway.modules.tinytot

import com.google.gson.JsonElement
import java.math.BigDecimal
import java.math.RoundingMode

/** Rupee amounts: lenient parsing of server values and Indian-grouped display. */
object UpiAmounts {
    private val CLEAN = Regex("[₹,\\s]|(?i)^(?:rs\\.?|inr)")
    private val DISPLAY_AMOUNT = Regex(
        "(?i)(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
    )
    private val MANUAL = Regex("^[0-9]+(?:\\.[0-9]{1,2})?$")

    /** Accepts JSON numbers or strings like "1499.50", "1,499", "₹ 1500". */
    fun parse(value: Any?): BigDecimal? {
        return when (value) {
            null -> null
            is BigDecimal -> value
            is Number -> runCatching { BigDecimal(value.toString()) }.getOrNull()
            is JsonElement -> when {
                value.isJsonNull -> null
                value.isJsonPrimitive -> parse(value.asJsonPrimitive.asString)
                else -> null
            }

            else -> {
                val cleaned = value.toString().trim().replace(CLEAN, "")
                if (cleaned.isEmpty()) null else runCatching { BigDecimal(cleaned) }.getOrNull()
            }
        }
    }

    /**
     * Parses an amount typed by the counter staff. Returns the decimal string the
     * server expects ("1499.50"), or null if it is not a positive amount with at most
     * two decimals.
     */
    fun parseManual(input: String?): String? {
        val cleaned = input?.trim()?.replace(",", "")?.removePrefix("₹")?.trim() ?: return null
        if (!MANUAL.matches(cleaned)) return null
        val amount = BigDecimal(cleaned)
        if (amount.signum() <= 0) return null
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
    }

    /** ₹1,23,456.50; whole rupees drop the decimals: ₹1,500. */
    fun format(amount: BigDecimal?): String {
        if (amount == null) return "₹—"

        val scaled = amount.setScale(2, RoundingMode.HALF_UP)
        val negative = scaled.signum() < 0
        val plain = scaled.abs().toPlainString()
        val intPart = plain.substringBefore('.')
        val fraction = plain.substringAfter('.', "00")

        val grouped = if (intPart.length <= 3) {
            intPart
        } else {
            val last3 = intPart.takeLast(3)
            val rest = intPart.dropLast(3)
            rest.reversed().chunked(2).joinToString(",").reversed() + "," + last3
        }

        val sign = if (negative) "-" else ""
        val decimals = if (fraction == "00") "" else ".$fraction"
        return "$sign₹$grouped$decimals"
    }

    /**
     * Best-effort amount for a local notification only. The server does the real
     * parsing and matching; this value is never sent or used for decisions.
     */
    fun extractDisplayAmount(smsBody: String): BigDecimal? {
        val match = DISPLAY_AMOUNT.find(smsBody) ?: return null
        return parse(match.groupValues[1])
    }
}
