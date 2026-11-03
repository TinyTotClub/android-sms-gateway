package me.capcom.smsgateway.modules.tinytot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpiTimeTest {
    private val base = 1762250400000L // 2025-11-04T10:00:00Z

    @Test
    fun parsesIsoVariants() {
        assertEquals(base, UpiTime.parseMillis("2025-11-04T10:00:00Z"))
        assertEquals(base + 123, UpiTime.parseMillis("2025-11-04T10:00:00.123Z"))
        assertEquals(base + 123, UpiTime.parseMillis("2025-11-04T10:00:00.123456+00:00"))
        assertEquals(base, UpiTime.parseMillis("2025-11-04 15:30:00+05:30"))
        assertEquals(base, UpiTime.parseMillis("2025-11-04T15:30:00+0530"))
        assertEquals(base, UpiTime.parseMillis("2025-11-04T12:00:00+02"))
        assertEquals(base + 500, UpiTime.parseMillis("2025-11-04T10:00:00.5"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(UpiTime.parseMillis(null))
        assertNull(UpiTime.parseMillis(""))
        assertNull(UpiTime.parseMillis("yesterday"))
        assertNull(UpiTime.parseMillis("2025-13-04T10:00:00Z"))
    }
}
