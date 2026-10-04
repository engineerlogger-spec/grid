package com.grid.app.core.time

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.LocalDate

/** Emits today's date, and again whenever the date changes (checked every 30 s while collected). */
fun AppClock.todayFlow(): Flow<LocalDate> = flow {
    while (true) {
        emit(today())
        delay(30_000)
    }
}.distinctUntilChanged()
