package com.grid.app.core.insights

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaymentChecksTest {

    private val t0 = 1_791_306_144_000L // 6 Oct 2026, 19:02 in Paris
    private fun p(id: Long, amount: Long, at: Long = t0, known: Boolean = true) = PaymentFacts(id, amount, at, known)

    @Test fun theSameChargeTwiceWithinMinutesIsFlagged() {
        assertThat(PaymentChecks.chargedTwice(p(2, 2320, t0 + 3 * 60_000), listOf(p(1, 2320)))).isEqualTo(PaymentFinding.ChargedTwice(t0))
    }

    @Test fun carWashTokensDayOnlyTimesAndFarApartChargesAreNotDoubleCharges() {
        assertThat(PaymentChecks.chargedTwice(p(2, 100, t0 + 60_000), listOf(p(1, 100)))).isNull() // €1 tokens
        assertThat(PaymentChecks.chargedTwice(p(2, 1000, known = false), listOf(p(1, 1000, known = false)))).isNull() // only the day known
        assertThat(PaymentChecks.chargedTwice(p(2, 2320, t0 + 30 * 60_000), listOf(p(1, 2320)))).isNull() // half an hour apart
        assertThat(PaymentChecks.chargedTwice(p(2, 2320, t0 + 60_000), listOf(p(1, 1970)))).isNull() // another amount
    }

    @Test fun aBillSaysWhatItUsuallyCostsOnlyWhenDifferent() {
        assertThat(PaymentChecks.billPaid(p(1, 1699), "SFR" to 1499)).isEqualTo(PaymentFinding.BillPaid("SFR", 1499))
        assertThat(PaymentChecks.billPaid(p(1, 1499), "SFR" to 1500)).isEqualTo(PaymentFinding.BillPaid("SFR", null))
        assertThat(PaymentChecks.billPaid(p(1, 1499), null)).isNull()
    }

    @Test fun aChargeFarAboveThisPayeesUsualIsFlagged() {
        assertThat(PaymentChecks.biggerThanUsual(p(1, 4500), listOf(1499, 1520, 1499))).isEqualTo(PaymentFinding.BiggerThanUsual(1499))
        assertThat(PaymentChecks.biggerThanUsual(p(1, 1900), listOf(1499, 1520, 1499))).isNull() // within 1.5×
        assertThat(PaymentChecks.biggerThanUsual(p(1, 4500), listOf(1499, 1520))).isNull() // too little history
        assertThat(PaymentChecks.biggerThanUsual(p(1, 1000), listOf(400, 450, 400))).isNull() // 1.5× but under €10 more
    }

    @Test fun aDoubleChargeComesBeforeTheBillAndTheBillBeforeTheAmount() {
        val twice = PaymentChecks.findings(p(2, 4500, t0 + 60_000), "SFR" to 1499, listOf(1499, 1499, 1499), listOf(p(1, 4500)))
        assertThat(twice).isInstanceOf(PaymentFinding.ChargedTwice::class.java)
        val bill = PaymentChecks.findings(p(2, 4500), "SFR" to 1499, listOf(1499, 1499, 1499), emptyList())
        assertThat(bill).isEqualTo(PaymentFinding.BillPaid("SFR", 1499))
    }
}
