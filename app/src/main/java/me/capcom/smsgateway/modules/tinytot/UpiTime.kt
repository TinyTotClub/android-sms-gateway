package me.capcom.smsgateway.modules.tinytot

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Parses Postgres/ISO-8601 timestamps ("2026-11-04T10:00:00.123456+00:00", "...Z",
 * "2026-11-04 10:00:00+05:30") to epoch ms on every API level (no java.time below 26,
 * and SimpleDateFormat's `X` needs API 24).
 */
object UpiTime {
    private val ISO = Regex(
        "^(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?\\s*(Z|[+-]\\d{2}(?::?\\d{2})?)?$",
        RegexOption.IGNORE_CASE,
    )

    fun parseMillis(value: String?): Long? {
        val m = ISO.matchEntire(value?.trim() ?: return null) ?: return null
        val (date, time, fraction, zone) = m.destructured

        val millis = fraction.padEnd(3, '0').take(3)
        val offset = when {
            zone.isEmpty() || zone.equals("Z", ignoreCase = true) -> "+0000"
            else -> {
                val digits = zone.replace(":", "")
                if (digits.length == 3) digits + "00" else digits
            }
        }

        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        return runCatching { format.parse("${date}T$time.$millis$offset")?.time }.getOrNull()
    }
}
