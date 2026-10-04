package com.grid.app.core.data.repo

import android.content.Context
import com.grid.app.R
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.prefs.SentLog
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.di.AppScope
import com.grid.app.core.insights.BudgetAlerts
import com.grid.app.core.insights.LimitStatus
import com.grid.app.core.model.TxType
import com.grid.app.core.money.MoneyFormatter
import com.grid.app.core.notify.Channels
import com.grid.app.core.notify.LaunchTarget
import com.grid.app.core.notify.Notifier
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriods
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * After every money change, checks the period's goal and each category limit and posts an alert
 * the first time 80% / 100% is reached. Runs off the caller's coroutine so saving stays instant.
 * Reads DAOs directly (not repositories) to avoid a dependency cycle with the listener set.
 */
@Singleton
class BudgetAlertListener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: GridDatabase,
    private val settings: SettingsRepository,
    private val sentLog: SentLog,
    private val notifier: Notifier,
    private val formatter: MoneyFormatter,
    private val clock: AppClock,
    @AppScope private val scope: CoroutineScope,
) : LedgerListener {

    private val mutex = Mutex()

    override suspend fun onLedgerChanged() {
        scope.launch { mutex.withLock { evaluate() } }
    }

    private suspend fun evaluate() {
        val s = settings.settings.first()
        if (!s.onboardingDone || !notifier.canPost) return
        val today = clock.today()
        val period = BudgetPeriods.periodFor(today, s.periodStartDay)
        val txs = db.transactionDao().between(period.startMillis(clock.zone), period.endMillis(clock.zone))
            .filter { it.type == TxType.EXPENSE && it.currency == s.currency }
        val categories = db.categoryDao().all()
        val goal = carriedGoal(db.planDao().observeAllPlans().first(), period)

        val statuses = buildList {
            if (goal != null) add(LimitStatus("total", null, goal, txs.sumOf { it.amountMinor }))
            categories.filter { (it.monthlyLimitMinor ?: 0) > 0 && !it.archived }.forEach { c ->
                add(LimitStatus("cat:${c.id}", c.name, c.monthlyLimitMinor!!, txs.filter { it.categoryId == c.id }.sumOf { it.amountMinor }))
            }
        }
        val alerts = BudgetAlerts.plan(period.start.toEpochDay(), statuses, sentLog.sentKeys())
        alerts.forEach { a ->
            val spent = formatter.format(a.spentMinor, s.currency)
            val limit = formatter.format(a.limitMinor, s.currency)
            val title = when {
                a.name == null && a.threshold >= 100 -> context.getString(R.string.alert_total_over)
                a.name == null -> context.getString(R.string.alert_total_80)
                a.threshold >= 100 -> context.getString(R.string.alert_category_over, a.name)
                else -> context.getString(R.string.alert_category_80, a.name)
            }
            notifier.post(Channels.BUDGET, a.keys.last().hashCode(), title, context.getString(R.string.alert_body, spent, limit), LaunchTarget.INSIGHTS)
            sentLog.markSent(a.keys, today)
        }
    }
}
