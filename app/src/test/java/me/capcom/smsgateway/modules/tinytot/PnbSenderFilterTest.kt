package me.capcom.smsgateway.modules.tinytot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PnbSenderFilterTest {
    @Test
    fun acceptsPnbSenders() {
        listOf(
            "JD-PNBSMS-S",
            "AX-PNBSMS",
            "PNBSMS",
            "VM-PNBBNK",
            "VM-PNBBNK-S",
            "PNBBNK",
            "pnbbnk",
            "BP-PNB",
            "BP-PNB-S",
            "PNB",
            "PNBCCD",
            "JM-PNBCCD",
            "JM-PNBCCD-T",
            "PNBCCD-S",
            "AD-PUNBNK",
            "Punjab National Bank",
            " jd-pnbsms-s ",
        ).forEach { assertTrue(it, PnbSenderFilter.isPnbSender(it)) }
    }

    @Test
    fun rejectsOtherSenders() {
        listOf(
            null,
            "",
            "+919876543210",
            "9876543210",
            "JD-HDFCBK",
            "AX-SBIUPI",
            "VM-PNBX",
            "PNB-S",
            "XYZ-PNB",
            "JD-PNB-X",
            "PNBCCD-X",
            "AMAZON",
            "VK-PAYTM",
        ).forEach { assertFalse(it.toString(), PnbSenderFilter.isPnbSender(it)) }
    }
}
