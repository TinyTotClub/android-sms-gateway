package me.capcom.smsgateway.modules.tinytot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpiSignerTest {
    // printf '%s' '1700000000000.{"action":"ping"}' | openssl dgst -sha256 -hmac test-key
    private val expected = "a7ee6a46b86f91ef47ddc3446a63008960adfdc5fd10492feb70ebf6f74e3305"

    @Test
    fun signsKnownVector() {
        assertEquals(expected, UpiSigner.sign("test-key", "1700000000000", """{"action":"ping"}"""))
    }

    @Test
    fun signsBytesAndStringsTheSame() {
        val body = """{"action":"ingest_sms","messages":[{"body":"₹ 1,500 credited"}]}"""
        assertEquals(
            UpiSigner.sign("k", "1", body),
            UpiSigner.sign("k", "1", body.toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun verifiesResponseSignature() {
        val body = """{"action":"ping"}""".toByteArray()
        assertTrue(UpiSigner.verify("test-key", "1700000000000", expected, body))
        assertTrue(UpiSigner.verify("test-key", "1700000000000", expected.uppercase(), body))
        assertTrue(UpiSigner.verify("test-key", " 1700000000000 ", " $expected ", body))
    }

    @Test
    fun rejectsBadResponseSignature() {
        val body = """{"action":"ping"}""".toByteArray()
        assertFalse(UpiSigner.verify("test-key", "1700000000000", null, body))
        assertFalse(UpiSigner.verify("test-key", null, expected, body))
        assertFalse(UpiSigner.verify("test-key", "", expected, body))
        assertFalse(UpiSigner.verify("other-key", "1700000000000", expected, body))
        assertFalse(UpiSigner.verify("test-key", "1700000000001", expected, body))
        assertFalse(UpiSigner.verify("test-key", "1700000000000", expected, """{"action":"pong"}""".toByteArray()))
        assertFalse(UpiSigner.verify("test-key", "1700000000000", expected.dropLast(1), body))
        assertFalse(UpiSigner.verify("test-key", "17e11", expected, body))
        assertFalse(UpiSigner.verify("", "1700000000000", expected, body))
    }
}
