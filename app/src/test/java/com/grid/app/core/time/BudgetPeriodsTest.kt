package com.grid.app.core.time

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

class BudgetPeriodsTest {

    private fun d(s: String) = LocalDate.parse(s)

    @Test fun calendarMonthWhenStartDayIsOne() {
        val p = BudgetPeriods.periodFor(d("2026-10-14"), 1)
        assertThat(p).isEqualTo(BudgetPeriod(d("2026-10-01"), d("2026-11-01")))
        assertThat(p.length).isEqualTo(31)
        assertThat(p.labelMonth).isEqualTo(YearMonth.of(2026, 10))
    }

    @Test fun payDayPeriodBeforeStartDay() {
        val p = BudgetPeriods.periodFor(d("2026-10-14"), 25)
        assertThat(p).isEqualTo(BudgetPeriod(d("2026-09-25"), d("2026-10-25")))
        assertThat(p.labelMonth).isEqualTo(YearMonth.of(2026, 10))
    }

    @Test fun payDayPeriodOnStartDay() {
        val p = BudgetPeriods.periodFor(d("2026-10-25"), 25)
        assertThat(p).isEqualTo(BudgetPeriod(d("2026-10-25"), d("2026-11-25")))
        assertThat(p.labelMonth).isEqualTo(YearMonth.of(2026, 11))
    }

    @Test fun startDayIsClampedTo28() {
        assertThat(BudgetPeriods.periodFor(d("2026-02-10"), 31))
            .isEqualTo(BudgetPeriod(d("2026-01-28"), d("2026-02-28")))
        assertThat(BudgetPeriods.periodFor(d("2026-02-10"), 0))
            .isEqualTo(BudgetPeriod(d("2026-02-01"), d("2026-03-01")))
    }

    @Test fun dayNumberAndDaysLeft() {
        val p = BudgetPeriods.periodFor(d("2026-10-14"), 1)
        assertThat(p.dayNumber(d("2026-10-14"))).isEqualTo(14)
        assertThat(p.daysLeft(d("2026-10-14"))).isEqualTo(18)
        assertThat(p.daysLeft(d("2026-10-31"))).isEqualTo(1)
        assertThat(p.daysLeft(d("2026-11-05"))).isEqualTo(0)
        assertThat(p.daysLeft(d("2026-09-20"))).isEqualTo(31)
    }

    @Test fun containsIsHalfOpen() {
        val p = BudgetPeriods.periodFor(d("2026-10-14"), 1)
        assertThat(d("2026-10-01") in p).isTrue()
        assertThat(d("2026-10-31") in p).isTrue()
        assertThat(d("2026-11-01") in p).isFalse()
    }

    @Test fun previousAndNext() {
        val p = BudgetPeriods.periodFor(d("2026-10-14"), 1)
        assertThat(BudgetPeriods.previous(p, 1)).isEqualTo(BudgetPeriod(d("2026-09-01"), d("2026-10-01")))
        assertThat(BudgetPeriods.next(p, 1)).isEqualTo(BudgetPeriod(d("2026-11-01"), d("2026-12-01")))
    }

    @Test fun yearBoundary() {
        assertThat(BudgetPeriods.periodFor(d("2027-01-05"), 25))
            .isEqualTo(BudgetPeriod(d("2026-12-25"), d("2027-01-25")))
    }

    @Test fun daysListsEveryDate() {
        val days = BudgetPeriods.periodFor(d("2026-02-10"), 1).days()
        assertThat(days).hasSize(28)
        assertThat(days.first()).isEqualTo(d("2026-02-01"))
        assertThat(days.last()).isEqualTo(d("2026-02-28"))
    }

    @Test fun millisBoundsUseZone() {
        val p = BudgetPeriods.periodFor(d("2026-10-14"), 1)
        val utc: ZoneId = ZoneOffset.UTC
        assertThat(p.startMillis(utc)).isEqualTo(d("2026-10-01").atStartOfDay(utc).toInstant().toEpochMilli())
        assertThat(p.endMillis(utc)).isEqualTo(d("2026-11-01").atStartOfDay(utc).toInstant().toEpochMilli())
    }

    @Test fun fixedClockReportsToday() {
        val clock = FixedClock(d("2026-10-14"))
        assertThat(clock.today()).isEqualTo(d("2026-10-14"))
    }
}
