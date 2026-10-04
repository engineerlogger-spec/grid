package com.grid.app.core.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object WorkScheduler {
    private const val DAILY = "grid.daily"
    private const val CATCH_UP = "grid.daily.catchup"
    private val DAILY_AT: LocalTime = LocalTime.of(9, 0)

    /** Daily run around 09:00 plus an immediate catch-up (charges missed while the phone was off). */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val periodic = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS, 2, TimeUnit.HOURS)
            .setInitialDelay(delayUntil(DAILY_AT).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, periodic)
        wm.enqueueUniqueWork(CATCH_UP, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<DailyWorker>().build())
    }

    /** Run the daily job now (e.g. right after a subscription or pending payment changes). */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(CATCH_UP, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<DailyWorker>().build())
    }

    internal fun delayUntil(time: LocalTime, now: LocalDateTime = LocalDateTime.now()): Duration {
        var next = now.toLocalDate().atTime(time)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}
