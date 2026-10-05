package com.grid.app.core.bills

import com.grid.app.core.data.db.entities.BankAccountEntity
import com.grid.app.core.data.prefs.AppSettings
import com.grid.app.core.data.prefs.LowFundsMode
import com.grid.app.core.data.prefs.SentLog
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.PendingRepository
import com.grid.app.core.data.repo.PlanRepository
import com.grid.app.core.data.repo.SubscriptionRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.PendingDirection
import com.grid.app.core.model.PendingPayment
import com.grid.app.core.model.Subscription
import com.grid.app.core.model.SubscriptionStatus
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.notify.Notifier
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriod
import com.grid.app.core.time.BudgetPeriods
import com.grid.app.core.time.todayFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A low-funds warning as shown on Home and in the notification. */
data class LowFundsState(val mode: LowFundsMode, val alert: LowFundsAlert, val currency: String)

/**
 * Will the money last until the end of the month? Bills still to pay (subscriptions not paid yet this month,
 * pending payments, monthly bills found in the bank history) against the Revolut balance or what is left of the
 * budget, as the user chose in Settings.
 */
@Singleton
class LowFundsMonitor @Inject constructor(
    private val settings: SettingsRepository,
    private val bank: BankRepository,
    private val subscriptions: SubscriptionRepository,
    private val pendings: PendingRepository,
    private val transactions: TransactionRepository,
    private val plans: PlanRepository,
    private val notifier: Notifier,
    private val sentLog: SentLog,
    private val clock: AppClock,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<LowFundsState?> = combine(settings.settings, clock.todayFlow()) { s, today -> s to today }
        .flatMapLatest { (s, today) ->
            if (s.lowFunds == LowFundsMode.OFF) return@flatMapLatest flowOf(null)
            val period = BudgetPeriods.periodFor(today, s.periodStartDay)
            combine(bank.observeAccounts(), subscriptions.observeAll(), pendings.observeAll(), transactions.observeAll(), plans.observeGoal(period)) { accounts, subs, pend, txs, goal ->
                evaluate(s, today, period, clock.zone, accounts, subs, pend, txs, goal)
            }
        }

    /** Notifies once per run-out date (a new date, after a new bill or a bigger spend, notifies again). */
    suspend fun notifyIfShort() {
        val state = observe().first() ?: return
        val key = "lowfunds:${state.alert.by.toEpochDay()}"
        if (!notifier.canPost || key in sentLog.sentKeys()) return
        notifier.lowFunds(state)
        sentLog.markSent(listOf(key), clock.today())
    }

    companion object {
        fun evaluate(
            s: AppSettings, today: LocalDate, period: BudgetPeriod, zone: ZoneId, accounts: List<BankAccountEntity>,
            subs: List<Subscription>, pendings: List<PendingPayment>, txs: List<Transaction>, goal: Long?,
        ): LowFundsState? {
            val currency = s.currency
            fun dateOf(tx: Transaction) = Instant.ofEpochMilli(tx.occurredAt).atZone(zone).toLocalDate()
            val spending = txs.filter { it.type == TxType.EXPENSE && it.currency == currency && it.source != TxSource.CHECKIN }

            val available = when (s.lowFunds) {
                LowFundsMode.BALANCE, LowFundsMode.BALANCE_AND_SPENDING ->
                    accounts.filter { it.enabled && it.currency == currency }.mapNotNull { it.balanceMinor }.takeIf { it.isNotEmpty() }?.sum() ?: return null
                LowFundsMode.BUDGET -> (goal ?: return null) - spending.filter { dateOf(it) in period }.sumOf { it.amountMinor }
                LowFundsMode.OFF -> return null
            }

            // Up to the end of the month, and at least a week ahead (rent on the 1st seen from the 28th).
            val until = maxOf(period.endExclusive.minusDays(1), today.plusDays(7))
            val forecasts = RecurringDetector.fromLedger(txs, today, zone)
            val planned = UpcomingPlanner.upcoming(today, ChronoUnit.DAYS.between(today, until).toInt(), subs, pendings, forecasts, currency)
                .filter { it.currency == currency && it.direction == PendingDirection.I_OWE }

            // This month's subscription charges: not again once paid, still to pay when their day passed unpaid.
            val payments = spending.map { SubscriptionPayment(it.subscriptionId, it.merchant ?: it.note, it.amountMinor, dateOf(it)) }
            val active = subs.filter { it.status == SubscriptionStatus.ACTIVE && it.currency == currency }
            val month = active.associate { it.id to SubscriptionMonth.check(it, period, today, payments) }
            val stillDue = active.mapNotNull { sub ->
                val m = month[sub.id] ?: return@mapNotNull null
                val dueOn = m.dueOn ?: return@mapNotNull null
                if (m.state == ChargeState.PAID || !dueOn.isBefore(today)) return@mapNotNull null
                UpcomingItem(UpcomingKind.SUBSCRIPTION, sub.id, sub.name, sub.amountMinor, currency, today, overdue = true, colorKey = sub.colorKey, iconKey = null)
            }
            val bills = planned.filterNot { it.kind == UpcomingKind.SUBSCRIPTION && month[it.id]?.let { m -> m.state == ChargeState.PAID && m.dueOn == it.date } == true } +
                stillDue.filter { late -> planned.none { it.kind == UpcomingKind.SUBSCRIPTION && it.id == late.id && it.date == today } }

            val daily = if (s.lowFunds == LowFundsMode.BALANCE_AND_SPENDING) dailySpending(spending, today, forecasts, active, ::dateOf) else 0L
            val alert = LowFunds.check(available, bills, daily, today, until) ?: return null
            return LowFundsState(s.lowFunds, alert, currency)
        }

        /** Everyday spending per day over the last 30 days: bills and subscriptions are counted on their own days instead. */
        private fun dailySpending(
            spending: List<Transaction>, today: LocalDate, forecasts: List<RecurringPayment>, subs: List<Subscription>, dateOf: (Transaction) -> LocalDate,
        ): Long {
            val billPayees = (forecasts.map { it.merchant } + subs.map { it.name }).mapNotNull(MerchantKey::of).toSet()
            val from = today.minusDays(DAILY_WINDOW_DAYS)
            return spending.filter { tx ->
                val d = dateOf(tx)
                !d.isBefore(from) && d.isBefore(today) && tx.subscriptionId == null && tx.merchant?.let(MerchantKey::of) !in billPayees
            }.sumOf { it.amountMinor } / DAILY_WINDOW_DAYS
        }

        private const val DAILY_WINDOW_DAYS = 30L
    }
}
