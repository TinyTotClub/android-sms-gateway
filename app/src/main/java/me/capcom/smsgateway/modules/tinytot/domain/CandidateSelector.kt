package me.capcom.smsgateway.modules.tinytot.domain

import java.math.BigDecimal

/**
 * Orders unmatched credits for the Verify dialog: credits that fit the intent come
 * first and the best one is preselected. A credit fits when its UTR equals the payer's
 * claimed UTR, the server listed the intent as a candidate, or its amount is within
 * ₹10 of the expected (or claimed) amount.
 */
object CandidateSelector {
    val AMOUNT_WINDOW: BigDecimal = BigDecimal.TEN

    data class Option(
        val alert: UpiAlert,
        val suggested: Boolean,
        val utrMatch: Boolean,
    )

    data class Selection(
        val options: List<Option>,
        /** Index into [options] to preselect, or null to default to manual entry. */
        val preselected: Int?,
    )

    fun select(intent: UpiIntent, alerts: List<UpiAlert>): Selection {
        val claimUtr = intent.claimUtr?.trim()?.takeIf { it.isNotEmpty() }

        val ranked = alerts.map { alert ->
            val utrMatch = claimUtr != null && alert.utr?.trim() == claimUtr
            val listed = intent.id in alert.candidateIntentIds
            val diff = amountDiff(intent, alert)
            val amountMatch = diff != null && diff <= AMOUNT_WINDOW
            Ranked(alert, utrMatch, listed, amountMatch, diff)
        }.sortedWith(
            compareByDescending<Ranked> { it.utrMatch }
                .thenByDescending { it.listed }
                .thenByDescending { it.amountMatch }
                .thenBy { it.diff ?: BigDecimal(Long.MAX_VALUE) }
                .thenByDescending { it.alert.receivedAt ?: 0L }
        )

        val options = ranked.map {
            Option(
                alert = it.alert,
                suggested = it.utrMatch || it.listed || it.amountMatch,
                utrMatch = it.utrMatch,
            )
        }

        return Selection(
            options = options,
            preselected = options.indexOfFirst { it.suggested }.takeIf { it >= 0 },
        )
    }

    /** Smallest distance to the expected or claimed amount. */
    private fun amountDiff(intent: UpiIntent, alert: UpiAlert): BigDecimal? {
        val amount = alert.amount ?: return null
        return listOfNotNull(intent.amountExpected, intent.claimAmount)
            .map { (it - amount).abs() }
            .minOrNull()
    }

    private data class Ranked(
        val alert: UpiAlert,
        val utrMatch: Boolean,
        val listed: Boolean,
        val amountMatch: Boolean,
        val diff: BigDecimal?,
    )
}
