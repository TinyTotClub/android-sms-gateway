package me.capcom.smsgateway.modules.tinytot.domain

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class UpiModelsTest {
    @Test
    fun parsesListOpen() {
        val json = JsonParser.parseString(
            """
            {
              "intents": [
                {"id": "i1", "reference": "TT-1", "description": null, "amount_expected": "1499.50",
                 "amount_tolerance": 1, "status": "claimed", "claim_utr": "123456789012",
                 "claim_amount": 1500, "created_at": "2025-11-04T10:00:00.123456+00:00",
                 "billing": {"id": "b1", "student_id": "s1", "description": "Nov fee"}},
                {"id": "i2", "amount_expected": 200, "status": "weird"},
                {"reference": "no id"}
              ],
              "alerts": [
                {"id": "a1", "amount": "1500.00", "utr": "123456789012", "payer_name": "RAHUL",
                 "match_status": "ambiguous", "candidate_intent_ids": ["i1", "i2"],
                 "received_at": "2025-11-04T10:01:00Z"}
              ]
            }
            """
        ).asJsonObject

        val items = OpenItems.fromJson(json)
        assertEquals(2, items.intents.size)

        val i1 = items.intents[0]
        assertEquals(0, BigDecimal("1499.5").compareTo(i1.amountExpected))
        assertEquals(0, BigDecimal("1500").compareTo(i1.claimAmount))
        assertEquals(UpiIntent.Status.Claimed, i1.status)
        assertEquals("Nov fee", i1.title)
        assertEquals("s1", i1.studentId)
        assertEquals("b1", i1.billingId)
        assertEquals(1762250400123L, i1.createdAt)
        assertEquals(UpiIntent.Status.Other, items.intents[1].status)

        val a1 = items.alerts.single()
        assertTrue(a1.isAmbiguous)
        assertEquals(listOf("i1", "i2"), a1.candidateIntentIds)
        assertEquals(0, BigDecimal("1500").compareTo(a1.amount))
    }

    @Test
    fun parsesIngestResults() {
        val stored = IngestResult.fromJson(
            JsonParser.parseString(
                """{"index":0,"status":"stored","matchStatus":"matched","intentStatus":"verified"}"""
            ).asJsonObject
        )!!
        assertTrue(stored.isFinal)
        assertEquals("verified", stored.intentStatus)

        val dup = IngestResult.fromJson(
            JsonParser.parseString("""{"index":1,"status":"duplicate"}""").asJsonObject
        )!!
        assertTrue(dup.isFinal)

        val odd = IngestResult.fromJson(
            JsonParser.parseString("""{"index":2,"status":"later"}""").asJsonObject
        )!!
        assertFalse(odd.isFinal)

        assertNull(IngestResult.fromJson(JsonParser.parseString("""{"status":"stored"}""").asJsonObject))
    }
}
