package com.grid.app.core.ai

import com.grid.app.core.bills.ChargeState
import com.grid.app.core.bills.LowFundsMonitor
import com.grid.app.core.bills.SubscriptionMonth
import com.grid.app.core.bills.SubscriptionPayment
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.PlanRepository
import com.grid.app.core.data.repo.SubscriptionRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.CycleUnit
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.SubscriptionStatus
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriods
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

@Serializable data class MonthLine(val month: String, val spent: Double, val income: Double, val byCategory: Map<String, Double>)
@Serializable data class PayeeLine(val payee: String, val category: String, val total: Double, val payments: Int)
@Serializable data class BillLine(
    val name: String, val expected: Double, val cadence: String, val varies: Boolean,
    val thisMonth: String, val lastPayments: List<Double>,
)
@Serializable data class TxLine(val date: String, val payee: String, val category: String, val amount: Double)
@Serializable data class DuplicateLine(val payee: String, val amount: Double, val dates: List<String>)

/**
 * The user's money in a few kilobytes, for Gemini to answer questions and write the monthly notes: totals by month
 * and category, main payees, bills with this month's status, the balance outlook and recent entries.
 * Payee names and amounts only: no account holder name, no IBAN.
 */
@Serializable
data class MoneySnapshot(
    val today: String,
    val currency: String,
    val balance: Double?,
    val salaryThisMonth: Double?,
    val leftToSpendThisMonth: Double?,
    val lowFunds: String?,
    val months: List<MonthLine>,
    val topPayeesLast90Days: List<PayeeLine>,
    val bills: List<BillLine>,
    val possibleDoubleCharges: List<DuplicateLine>,
    val recent: List<TxLine>,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    private companion object {
        val json = Json { encodeDefaults = true }
    }
}

@Singleton
class MoneySnapshots @Inject constructor(
    private val settings: SettingsRepository,
    private val transactions: TransactionRepository,
    private val subscriptions: SubscriptionRepository,
    private val bank: BankRepository,
    private val plans: PlanRepository,
    private val lowFunds: LowFundsMonitor,
    private val clock: AppClock,
) {
    suspend fun build(): MoneySnapshot {
        val s = settings.settings.first()
        val today = clock.today()
        val zone = clock.zone
        fun dateOf(tx: Transaction): LocalDate = Instant.ofEpochMilli(tx.occurredAt).atZone(zone).toLocalDate()
        fun eur(minor: Long) = minor / 100.0

        val all = transactions.observeAll().first().filter { it.currency == s.currency }
        val entries = all.filter { it.source != TxSource.CHECKIN }
        val period = BudgetPeriods.periodFor(today, s.periodStartDay)

        // The last six budget months, newest first.
        val months = generateSequence(period) { BudgetPeriods.previous(it, s.periodStartDay) }.take(6).map { p ->
            val inMonth = entries.filter { dateOf(it) in p }
            val spent = inMonth.filter { it.type == TxType.EXPENSE }
            MonthLine(
                month = p.labelMonth.toString(),
                spent = eur(spent.sumOf { it.amountMinor }),
                income = eur(inMonth.filter { it.type == TxType.INCOME }.sumOf { it.amountMinor }),
                byCategory = spent.groupBy { it.category.name }.mapValues { (_, t) -> eur(t.sumOf { it.amountMinor }) }
                    .entries.sortedByDescending { it.value }.take(10).associate { it.key to it.value },
            )
        }.toList()

        val since90 = today.minusDays(90)
        val payees = entries.filter { it.type == TxType.EXPENSE && !dateOf(it).isBefore(since90) && it.merchant != null }
            .groupBy { MerchantKey.of(it.merchant!!) }
            .map { (_, t) -> PayeeLine(t.maxBy { it.occurredAt }.merchant!!, t.first().category.name, eur(t.sumOf { it.amountMinor }), t.size) }
            .sortedByDescending { it.total }.take(25)

        val subs = subscriptions.all().filter { it.status == SubscriptionStatus.ACTIVE && it.currency == s.currency }
        val payments = entries.filter { it.type == TxType.EXPENSE }.map { SubscriptionPayment(it.subscriptionId, it.merchant ?: it.note, it.amountMinor, dateOf(it)) }
        val bills = subs.map { sub ->
            val m = SubscriptionMonth.check(sub, period, today, payments)
            BillLine(
                name = sub.name, expected = eur(sub.amountMinor),
                cadence = when (sub.cycle.unit) {
                    CycleUnit.WEEK -> "every ${sub.cycle.count} week(s)"
                    CycleUnit.MONTH -> if (sub.cycle.count == 1) "monthly" else "every ${sub.cycle.count} months"
                    CycleUnit.YEAR -> "yearly"
                },
                varies = sub.amountVaries,
                thisMonth = when (m.state) {
                    ChargeState.PAID -> "paid on ${m.paidOn}"
                    ChargeState.DUE -> "due on ${m.dueOn}"
                    ChargeState.LATE -> "not paid yet, was due on ${m.dueOn}"
                    ChargeState.NONE -> "nothing due this month"
                },
                lastPayments = entries.filter { it.subscriptionId == sub.id }.sortedByDescending { it.occurredAt }.take(4).map { eur(it.amountMinor) },
            )
        }

        // Same payee, same amount, within three days this month: worth a look.
        val thisMonth = entries.filter { it.type == TxType.EXPENSE && dateOf(it) in period && it.merchant != null }
        val duplicates = thisMonth.groupBy { MerchantKey.of(it.merchant!!) to it.amountMinor }.values
            .filter { group -> group.size >= 2 && group.zipWithNext().any { (a, b) -> ChronoUnit.DAYS.between(dateOf(a), dateOf(b)).let { it in -3..3 } } && group.first().amountMinor >= DUPLICATE_MIN_MINOR }
            .map { g -> DuplicateLine(g.first().merchant!!, eur(g.first().amountMinor), g.map { dateOf(it).toString() }) }

        val balance = bank.accounts().filter { it.enabled && it.currency == s.currency }.mapNotNull { it.balanceMinor }.takeIf { it.isNotEmpty() }?.sum()
        val salary = all.filter { it.source == TxSource.CHECKIN && it.type == TxType.INCOME && dateOf(it) in period }.sumOf { it.amountMinor }.takeIf { it > 0 }
        val goal = plans.goalFor(period)
        val spentThisMonth = thisMonth.sumOf { it.amountMinor }
        val alert = lowFunds.observe().first()?.alert

        return MoneySnapshot(
            today = today.toString(),
            currency = s.currency,
            balance = balance?.let(::eur),
            salaryThisMonth = salary?.let(::eur),
            leftToSpendThisMonth = goal?.let { eur(it - spentThisMonth) },
            lowFunds = alert?.let { "short by ${eur(it.shortMinor)} on ${it.by}" + (it.firstUncovered?.let { b -> " (for ${b.title})" } ?: "") },
            months = months,
            topPayeesLast90Days = payees,
            bills = bills,
            possibleDoubleCharges = duplicates,
            recent = entries.sortedByDescending { it.occurredAt }.take(RECENT).map { tx ->
                TxLine(dateOf(tx).toString(), tx.merchant ?: tx.note ?: tx.category.name, tx.category.name, eur(if (tx.type == TxType.EXPENSE) -tx.amountMinor else tx.amountMinor).let { (it * 100).roundToLong() / 100.0 })
            },
        )
    }

    private companion object {
        const val RECENT = 80
        const val DUPLICATE_MIN_MINOR = 500L
    }
}
