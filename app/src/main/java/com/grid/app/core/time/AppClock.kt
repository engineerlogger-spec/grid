package com.grid.app.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Source of "now". Inject this instead of calling `Instant.now()` so date logic stays testable. */
interface AppClock {
    fun now(): Instant
    val zone: ZoneId
    fun today(): LocalDate = now().atZone(zone).toLocalDate()
    fun millis(): Long = now().toEpochMilli()
}

object SystemAppClock : AppClock {
    override fun now(): Instant = Instant.now()
    override val zone: ZoneId get() = ZoneId.systemDefault()
}

/** A clock pinned to [date] at noon (tests, previews). */
class FixedClock(date: LocalDate, override val zone: ZoneId = ZoneId.of("UTC")) : AppClock {
    private val instant = date.atTime(12, 0).atZone(zone).toInstant()
    override fun now(): Instant = instant
}
