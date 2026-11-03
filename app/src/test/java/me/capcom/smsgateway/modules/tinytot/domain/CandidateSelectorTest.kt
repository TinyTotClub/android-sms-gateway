package me.capcom.smsgateway.modules.tinytot.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CandidateSelectorTest {
    private fun intent(
        id: String = "i1",
        expected: String = "1500",
        claimUtr: String? = null,
        claimAmount: String? = null,
    ) = UpiIntent(
        id = id,
        reference = "REF",
        description = null,
        studentId = null,
        billingId = null,
        amountExpected = BigDecimal(expected),
        amountTolerance = null,
        status = UpiIntent.Status.Pending,
        claimUtr = claimUtr,
        claimAmount = claimAmount?.let { BigDecimal(it) },
        createdAt = null,
        claimedAt = null,
        expiresAt = null,
        billingDescription = null,
    )

    private fun alert(
        id: String,
        amount: String?,
        utr: String? = null,
        candidates: List<String> = emptyList(),
        receivedAt: Long = 0,
    ) = UpiAlert(
        id = id,
        sender = "JD-PNBSMS-S",
        receivedAt = receivedAt,
        amount = amount?.let { BigDecimal(it) },
        utr = utr,
        payerName = null,
        accountLast4 = null,
        matchStatus = "unmatched",
        candidateIntentIds = candidates,
    )

    @Test
    fun utrMatchWinsOverCloserAmount() {
        val selection = CandidateSelector.select(
            intent(claimUtr = "123456789012"),
            listOf(alert("exact", "1500"), alert("utr", "900", utr = "123456789012")),
        )
        assertEquals("utr", selection.options[0].alert.id)
        assertTrue(selection.options[0].utrMatch)
        assertEquals(0, selection.preselected)
    }

    @Test
    fun amountWithinTenRupeesIsSuggested() {
        val selection = CandidateSelector.select(
            intent(expected = "1499.50"),
            listOf(alert("far", "1600"), alert("near", "1509.50"), alert("closest", "1500")),
        )
        assertEquals(listOf("closest", "near", "far"), selection.options.map { it.alert.id })
        assertEquals(listOf(true, true, false), selection.options.map { it.suggested })
        assertEquals(0, selection.preselected)
    }

    @Test
    fun boundaryIsInclusive() {
        val selection = CandidateSelector.select(
            intent(expected = "1000"),
            listOf(alert("ten", "1010"), alert("eleven", "1011")),
        )
        assertTrue(selection.options.first { it.alert.id == "ten" }.suggested)
        assertFalse(selection.options.first { it.alert.id == "eleven" }.suggested)
    }

    @Test
    fun claimedAmountAlsoCounts() {
        val selection = CandidateSelector.select(
            intent(expected = "1500", claimAmount = "1200"),
            listOf(alert("claimed", "1200")),
        )
        assertEquals(0, selection.preselected)
    }

    @Test
    fun serverCandidateIsSuggested() {
        val selection = CandidateSelector.select(
            intent(id = "i9", expected = "1500"),
            listOf(alert("other", "1500"), alert("listed", "2000", candidates = listOf("i9"))),
        )
        assertEquals("listed", selection.options[0].alert.id)
        assertEquals(0, selection.preselected)
    }

    @Test
    fun noFitMeansManualEntry() {
        val selection = CandidateSelector.select(
            intent(expected = "1500"),
            listOf(alert("a", "100"), alert("b", null)),
        )
        assertEquals(2, selection.options.size)
        assertTrue(selection.options.none { it.suggested })
        assertNull(selection.preselected)
        assertEquals("a", selection.options[0].alert.id)
    }

    @Test
    fun emptyAlerts() {
        val selection = CandidateSelector.select(intent(), emptyList())
        assertTrue(selection.options.isEmpty())
        assertNull(selection.preselected)
    }
}
