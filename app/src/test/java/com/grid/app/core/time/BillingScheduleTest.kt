package com.grid.app.core.time

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.model.Cycle
import com.grid.app.core.model.CycleUnit
import org.junit.Test
import java.time.LocalDate

class BillingScheduleTest {

    private fun d(s: String) = LocalDate.parse(s)
    private val monthly = Cycle(CycleUnit.MONTH, 1)
    private val weekly = Cycle(CycleUnit.WEEK, 1)
    private val quarterly = Cycle(CycleUnit.MONTH, 3)
    private val yearly = Cycle(CycleUnit.YEAR, 1)

    @Test fun monthEndAnchorSurvivesShortMonths() {
        val anchor = d("2026-01-31")
        assertThat((0..3).map { BillingSchedule.chargeDate(anchor, monthly, it) })
            .containsExactly(d("2026-01-31"), d("2026-02-28"), d("2026-03-31"), d("2026-04-30")).inOrder()
    }

    @Test fun weeklyAndQuarterly() {
        assertThat(BillingSchedule.chargeDate(d("2026-10-01"), weekly, 2)).isEqualTo(d("2026-10-15"))
        assertThat(BillingSchedule.chargeDate(d("2026-10-01"), quarterly, 1)).isEqualTo(d("2027-01-01"))
    }

    @Test fun leapDayYearly() {
        val anchor = d("2028-02-29")
        assertThat(BillingSchedule.chargeDate(anchor, yearly, 1)).isEqualTo(d("2029-02-28"))
        assertThat(BillingSchedule.chargeDate(anchor, yearly, 4)).isEqualTo(d("2032-02-29"))
    }

    @Test fun nextOnOrAfterIncludesTheDayItself() {
        val anchor = d("2026-01-15")
        assertThat(BillingSchedule.nextOnOrAfter(anchor, monthly, d("2026-10-15"))).isEqualTo(d("2026-10-15"))
        assertThat(BillingSchedule.nextOnOrAfter(anchor, monthly, d("2026-10-16"))).isEqualTo(d("2026-11-15"))
        assertThat(BillingSchedule.nextOnOrAfter(anchor, monthly, d("2025-12-01"))).isEqualTo(d("2026-01-15"))
    }

    @Test fun nextAfterIsStrict() {
        assertThat(BillingSchedule.nextAfter(d("2026-01-15"), monthly, d("2026-10-15"))).isEqualTo(d("2026-11-15"))
    }

    @Test fun catchUpListsEveryMissedCharge() {
        val charges = BillingSchedule.chargesThrough(d("2026-01-31"), monthly, from = d("2026-07-31"), through = d("2026-10-04"))
        assertThat(charges).containsExactly(d("2026-07-31"), d("2026-08-31"), d("2026-09-30")).inOrder()
    }

    @Test fun catchUpEmptyWhenNothingDue() {
        assertThat(BillingSchedule.chargesThrough(d("2026-01-15"), monthly, from = d("2026-10-15"), through = d("2026-10-04"))).isEmpty()
    }

    @Test fun monthlyEquivalents() {
        assertThat(BillingSchedule.monthlyEquivalent(1000, weekly)).isEqualTo(4333)     // 10 × 52 / 12
        assertThat(BillingSchedule.monthlyEquivalent(12000, yearly)).isEqualTo(1000)
        assertThat(BillingSchedule.monthlyEquivalent(3000, quarterly)).isEqualTo(1000)
        assertThat(BillingSchedule.monthlyEquivalent(1399, monthly)).isEqualTo(1399)
        assertThat(BillingSchedule.yearly(1399, monthly)).isEqualTo(16788)
    }
}
