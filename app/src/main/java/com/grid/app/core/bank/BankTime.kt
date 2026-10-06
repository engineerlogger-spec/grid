package com.grid.app.core.bank

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * When a bank payment happened. Revolut's transaction ids are UUIDs whose first 8 hex digits are the Unix second the
 * payment was made ("6ac529a0-…" = 6 Oct 2026, 17:02:24 UTC, the 19:02 the Revolut app shows); banks that give only a
 * day get a day-only marker, which is never shown as a time.
 */
object BankTime {
    private val TIMED_ID = Regex("""([0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

    /** The payment's instant from its id ("p:" and other prefixes allowed), when the id carries one on [date] (± a day). */
    fun fromId(externalId: String, date: LocalDate, zone: ZoneId): Long? {
        val hex = TIMED_ID.matchEntire(externalId.substringAfterLast(':'))?.groupValues?.get(1) ?: return null
        val instant = Instant.ofEpochSecond(hex.toLong(16))
        return instant.toEpochMilli().takeIf { abs(ChronoUnit.DAYS.between(date, instant.atZone(zone).toLocalDate())) <= 1 }
    }

    /** Only the day is known: noon, or midnight for today before noon (never a time that looks real or lies ahead). */
    fun dayOnly(date: LocalDate, zone: ZoneId, now: Long): Long {
        val noon = date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        return if (noon <= now) noon else date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** True for [dayOnly] values: show the day, not a time. */
    fun isDayOnly(at: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(at).atZone(zone).toLocalTime().let { it == LocalTime.NOON || it == LocalTime.MIDNIGHT }
}
