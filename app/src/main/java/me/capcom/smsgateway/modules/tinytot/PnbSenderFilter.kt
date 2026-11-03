package me.capcom.smsgateway.modules.tinytot

/**
 * Decides whether an SMS sender is a Punjab National Bank sender id. Only these
 * messages ever leave the phone; OTPs and personal SMS are never forwarded.
 *
 * Mirrors `canHandlePnbSender` in the `upi-verifier` Edge Function.
 */
object PnbSenderFilter {
    private val SUBSTRINGS = listOf("PUNJAB NATIONAL BANK", "PNBBNK", "PUNBN", "PNBSMS")
    private val EXACT = setOf("PNB", "PNBBNK")
    private val PATTERNS = listOf(
        Regex("^[A-Z]{2}-PNB(?:BNK)?(?:-S)?$"),
        Regex("^(?:[A-Z]{2}-)?PNBCCD(?:-[ST])?$"),
    )

    fun isPnbSender(sender: String?): Boolean {
        val s = sender?.trim()?.uppercase() ?: return false
        if (s.isEmpty()) return false

        return SUBSTRINGS.any { s.contains(it) }
                || s in EXACT
                || PATTERNS.any { it.matches(s) }
    }
}
