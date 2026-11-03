package me.capcom.smsgateway.modules.tinytot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdsTest {
    @Test
    fun slugsModelNames() {
        assertEquals("Pixel-7a", DeviceIds.slug("Pixel 7a"))
        assertEquals("SM-A515F", DeviceIds.slug("SM-A515F"))
        assertEquals("Redmi-Note-12-Pro", DeviceIds.slug("  Redmi Note 12 Pro+ "))
        assertEquals("upi-verifier", DeviceIds.slug(""))
        assertEquals("upi-verifier", DeviceIds.slug(null))
        assertEquals(64, DeviceIds.slug("x".repeat(100)).length)
    }

    @Test
    fun validatesIds() {
        assertTrue(DeviceIds.isValid("office-phone_1.2"))
        assertFalse(DeviceIds.isValid(""))
        assertFalse(DeviceIds.isValid("office phone"))
        assertFalse(DeviceIds.isValid("x".repeat(65)))
    }
}
