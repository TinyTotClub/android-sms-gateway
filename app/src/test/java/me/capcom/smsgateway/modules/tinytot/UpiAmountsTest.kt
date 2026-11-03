package me.capcom.smsgateway.modules.tinytot

import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class UpiAmountsTest {
    private fun bd(s: String) = BigDecimal(s)

    @Test
    fun parsesLeniently() {
        assertEquals(0, bd("1499.5").compareTo(UpiAmounts.parse("1499.50")))
        assertEquals(0, bd("1500").compareTo(UpiAmounts.parse(1500)))
        assertEquals(0, bd("1500.25").compareTo(UpiAmounts.parse(1500.25)))
        assertEquals(0, bd("123456").compareTo(UpiAmounts.parse("1,23,456")))
        assertEquals(0, bd("1500").compareTo(UpiAmounts.parse("₹ 1,500")))
        assertEquals(0, bd("1500").compareTo(UpiAmounts.parse("Rs.1500")))
        assertEquals(0, bd("1500").compareTo(UpiAmounts.parse("INR 1500")))
        assertEquals(0, bd("1500").compareTo(UpiAmounts.parse(JsonPrimitive(1500))))
        assertEquals(0, bd("1500.5").compareTo(UpiAmounts.parse(JsonPrimitive("1500.50"))))
    }

    @Test
    fun parseReturnsNullForGarbage() {
        assertNull(UpiAmounts.parse(null))
        assertNull(UpiAmounts.parse(""))
        assertNull(UpiAmounts.parse("abc"))
        assertNull(UpiAmounts.parse(JsonNull.INSTANCE))
        assertNull(UpiAmounts.parse(JsonParser.parseString("{}")))
    }

    @Test
    fun parsesManualInput() {
        assertEquals("1500.00", UpiAmounts.parseManual("1500"))
        assertEquals("1499.50", UpiAmounts.parseManual(" 1,499.5 "))
        assertEquals("10.05", UpiAmounts.parseManual("₹10.05"))
        assertNull(UpiAmounts.parseManual("0"))
        assertNull(UpiAmounts.parseManual("-5"))
        assertNull(UpiAmounts.parseManual("1.234"))
        assertNull(UpiAmounts.parseManual("12a"))
        assertNull(UpiAmounts.parseManual(""))
        assertNull(UpiAmounts.parseManual(null))
    }

    @Test
    fun formatsWithIndianGrouping() {
        assertEquals("₹0", UpiAmounts.format(bd("0")))
        assertEquals("₹999", UpiAmounts.format(bd("999")))
        assertEquals("₹1,500", UpiAmounts.format(bd("1500.00")))
        assertEquals("₹1,499.50", UpiAmounts.format(bd("1499.5")))
        assertEquals("₹1,23,456", UpiAmounts.format(bd("123456")))
        assertEquals("₹12,34,567.89", UpiAmounts.format(bd("1234567.89")))
        assertEquals("₹1,00,00,000", UpiAmounts.format(bd("10000000")))
        assertEquals("-₹1,500", UpiAmounts.format(bd("-1500")))
        assertEquals("₹—", UpiAmounts.format(null))
    }

    @Test
    fun extractsDisplayAmountFromSms() {
        val body = "Your A/c XX1234 has been credited with Rs.1,500.00 on 04-11-2026 by UPI. Avl Bal Rs 25,000.00"
        assertEquals(0, bd("1500").compareTo(UpiAmounts.extractDisplayAmount(body)))
        assertNull(UpiAmounts.extractDisplayAmount("Your OTP is 123456"))
    }
}
