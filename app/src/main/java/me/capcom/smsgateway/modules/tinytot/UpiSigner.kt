package me.capcom.smsgateway.modules.tinytot

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 signing shared with the `upi-verifier` Edge Function:
 * `hex(HMAC(key, "<timestamp>.<raw body>"))`, used for requests and responses.
 */
object UpiSigner {
    const val HEADER_TIMESTAMP = "x-upi-timestamp"
    const val HEADER_SIGNATURE = "x-upi-signature"
    const val HEADER_DEVICE = "x-upi-device"

    private const val ALGORITHM = "HmacSHA256"

    fun sign(key: String, timestamp: String, rawBody: ByteArray): String {
        require(key.isNotEmpty()) { "API key is empty" }

        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), ALGORITHM))
        mac.update(timestamp.toByteArray(Charsets.UTF_8))
        mac.update('.'.code.toByte())
        val digest = mac.doFinal(rawBody)

        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sign(key: String, timestamp: String, rawBody: String): String =
        sign(key, timestamp, rawBody.toByteArray(Charsets.UTF_8))

    /** Constant-time check of a server response signature. */
    fun verify(key: String, timestamp: String?, signature: String?, rawBody: ByteArray): Boolean {
        val ts = timestamp?.trim()
        val sig = signature?.trim()?.lowercase()
        if (ts.isNullOrEmpty() || sig.isNullOrEmpty() || key.isEmpty()) return false
        if (!ts.all { it.isDigit() }) return false

        val expected = sign(key, ts, rawBody)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            sig.toByteArray(Charsets.US_ASCII),
        )
    }
}
