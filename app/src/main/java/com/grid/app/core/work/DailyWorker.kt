package com.grid.app.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.grid.app.core.bills.LowFundsMonitor
import com.grid.app.core.bills.ReminderPlanner
import com.grid.app.core.data.prefs.SentLog
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.PendingRepository
import com.grid.app.core.data.repo.PlanRepository
import com.grid.app.core.data.repo.SubscriptionRepository
import com.grid.app.core.notify.Notifier
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriods
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Once a day (and once at app start as a catch-up): books due subscription charges, posts bill
 * reminders, and nudges the monthly income check-in. Every step is idempotent.
 */
@HiltWorker
class DailyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val subscriptions: SubscriptionRepository,
    private val pendings: PendingRepository,
    private val plans: PlanRepository,
    private val settings: SettingsRepository,
    private val sentLog: SentLog,
    private val notifier: Notifier,
    private val lowFunds: LowFundsMonitor,
    private val clock: AppClock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val today = clock.today()
        val s = settings.settings.first()
        if (!s.onboardingDone) return Result.success()

        subscriptions.processDueCharges(today)

        val sent = sentLog.sentKeys()
        val reminders = ReminderPlanner.plan(today, subscriptions.all(), pendings.all(), sent)
        if (notifier.canPost) {
            reminders.forEach(notifier::reminder)
            sentLog.markSent(reminders.map { it.key }, today)
        }

        lowFunds.notifyIfShort()

        val period = BudgetPeriods.periodFor(today, s.periodStartDay)
        val checkInKey = "checkin:${period.start.toEpochDay()}"
        if (notifier.canPost && checkInKey !in sent && plans.observeNeedsCheckIn(period).first()) {
            notifier.checkIn()
            sentLog.markSent(listOf(checkInKey), today)
        }
        return Result.success()
    }
}
