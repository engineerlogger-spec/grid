package com.grid.app.core.bank

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class BankTimeTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val day = LocalDate.parse("2026-10-06")
    private fun local(at: Long) = Instant.ofEpochMilli(at).atZone(paris).toLocalTime()

    @Test fun revolutIdsCarryTheSecondOfThePayment() {
        // The owner's two Bolt rides of 6 October, at 19:02 and 20:03 in the Revolut app.
        assertThat(local(BankTime.fromId("6ac529a0-5dcf-a4ab-9a25-9d4a03329917", day, paris)!!)).isEqualTo(LocalTime.of(19, 2, 24))
        assertThat(local(BankTime.fromId("p:6ac537f8-224a-a751-b4f9-86aaa48a9996", day, paris)!!)).isEqualTo(LocalTime.of(20, 3, 36))
    }

    @Test fun idsWithoutATimeOrFarFromTheDateGiveNone() {
        assertThat(BankTime.fromId("h:3f2a#0", day, paris)).isNull()
        assertThat(BankTime.fromId("e-9", day, paris)).isNull()
        assertThat(BankTime.fromId("00000000-0000-4000-8000-000000000000", day, paris)).isNull()
    }

    @Test fun aDayOnlyTimeIsNeverMistakenForARealOne() {
        val nineAm = day.atTime(9, 0).atZone(paris).toInstant().toEpochMilli()
        val today = BankTime.dayOnly(day, paris, nineAm)
        assertThat(today).isAtMost(nineAm)
        assertThat(BankTime.isDayOnly(today, paris)).isTrue()
        assertThat(BankTime.isDayOnly(BankTime.dayOnly(day.minusDays(1), paris, nineAm), paris)).isTrue()
        assertThat(BankTime.isDayOnly(day.atTime(19, 2, 24).atZone(paris).toInstant().toEpochMilli(), paris)).isFalse()
    }
}
