package com.grid.app.core.notify

import android.content.Context
import com.grid.app.R
import com.grid.app.core.bank.BankTime
import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.entities.BankTransactionEntity
import com.grid.app.core.data.prefs.AppSettings
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.PlanRepository
import com.grid.app.core.insights.PaymentChecks
import com.grid.app.core.insights.PaymentFacts
import com.grid.app.core.insights.PaymentFinding
import com.grid.app.core.model.BankTxState
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.money.MoneyFormatter
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriods
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * The check after paying: one notification per payment out the bank just listed (made in the last 2 hours) with what's
 * left this month, and a bill paid, an unusual amount or a double charge when there is one. It replaces the payment's
 * own "Added…" / "Detected…" alert, so a payment never makes two notifications.
 */
@Singleton
class PaymentCheckNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: GridDatabase,
    private val notifier: Notifier,
    private val formatter: MoneyFormatter,
    private val plans: PlanRepository,
    private val settings: SettingsRepository,
    private val clock: AppClock,
) {
    private val bankDao = db.bankDao()

    /** [rowIds]: the bank rows a sync listed for the first time. */
    suspend fun notifyFresh(rowIds: List<Long>) {
        val now = clock.millis()
        val fresh = rowIds.mapNotNull { bankDao.staged(it) }.filter {
            it.direction == CaptureDirection.OUT && it.state == BankTxState.BOOKED && it.transactionId != null && it.occurredAt >= now - FRESH_MS
        }
        if (fresh.isEmpty()) return
        val s = settings.settings.first()
        if (fresh.size > MAX_SINGLE) {
            val title = context.resources.getQuantityString(R.plurals.check_summary, fresh.size, fresh.size, formatter.format(fresh.sumOf { it.amountMinor }, s.currency))
            notifier.post(Channels.PAYMENTS, NotificationIds.PAYMENTS_SUMMARY, title, monthLine(null, s), LaunchTarget.ACTIVITY)
            return
        }
        fresh.forEach { notifyOne(it, s) }
    }

    private suspend fun notifyOne(row: BankTransactionEntity, s: AppSettings) {
        val entry = row.transactionId?.let { db.transactionDao().get(it) } ?: return
        val name = entry.merchant ?: BankRepository.displayName(row) ?: return
        val amount = formatter.format(row.amountMinor, row.currency)
        val payment = facts(row)
        val bill = entry.subscriptionId?.let { db.subscriptionDao().get(it) }?.let { it.name to it.amountMinor }
        // Earlier payments to the same payee (what it usually takes), and any within minutes (a double charge).
        val samePayee = row.counterpartyKey?.let { key ->
            bankDao.stagedBetween(row.occurredAt - HISTORY_MS, row.occurredAt + TWICE_MS)
                .filter { it.id != row.id && it.counterpartyKey == key && it.direction == CaptureDirection.OUT && it.state == BankTxState.BOOKED }
        }.orEmpty()
        val earlier = samePayee.filter { it.occurredAt < row.occurredAt - TWICE_MS }.map { it.amountMinor }
        val near = samePayee.filter { abs(it.occurredAt - row.occurredAt) <= TWICE_MS }.map(::facts)
        val finding = PaymentChecks.findings(payment, bill, earlier, near)

        val title = when (finding) {
            is PaymentFinding.ChargedTwice -> context.getString(R.string.check_twice_title, name, amount)
            is PaymentFinding.BillPaid -> context.getString(R.string.check_bill_title, finding.name, amount)
            else -> context.getString(R.string.check_title, name, amount)
        }
        val detail = when (finding) {
            is PaymentFinding.BillPaid -> finding.usualMinor?.let { context.getString(R.string.check_bill_usual, formatter.format(it, row.currency)) }
            is PaymentFinding.BiggerThanUsual -> context.getString(R.string.check_bigger, formatter.format(finding.usualMinor, row.currency))
            is PaymentFinding.ChargedTwice -> context.getString(R.string.check_twice_body, timeOf(finding.otherAt))
            null -> null
        }
        val body = listOfNotNull(detail, monthLine(entry.categoryId, s)).joinToString("\n")
        val id = entry.captureId?.let(NotificationIds::capture) ?: NotificationIds.paymentCheck(entry.id)
        notifier.post(Channels.PAYMENTS, id, title, body, LaunchTarget.ACTIVITY)
    }

    private fun facts(row: BankTransactionEntity) = PaymentFacts(row.id, row.amountMinor, row.occurredAt, !BankTime.isDayOnly(row.occurredAt, clock.zone))

    /** What's left of the category's monthly limit, else of the monthly goal, else what's spent this month. */
    private suspend fun monthLine(categoryId: Long?, s: AppSettings): String {
        val period = BudgetPeriods.periodFor(clock.today(), s.periodStartDay)
        val spent = db.transactionDao().between(period.startMillis(clock.zone), period.endMillis(clock.zone))
            .filter { it.type == TxType.EXPENSE && it.source != TxSource.CHECKIN }
        val category = categoryId?.let { db.categoryDao().get(it) }
        val limit = category?.monthlyLimitMinor?.takeIf { it > 0 }
        if (category != null && limit != null) {
            val left = limit - spent.filter { it.categoryId == category.id }.sumOf { it.amountMinor }
            return if (left >= 0) context.getString(R.string.check_category_left, category.name, money(left, s), money(limit, s))
            else context.getString(R.string.check_category_over, category.name, money(-left, s), money(limit, s))
        }
        val total = spent.sumOf { it.amountMinor }
        val goal = plans.goalFor(period)
        return when {
            goal == null -> context.getString(R.string.check_spent, money(total, s))
            goal >= total -> context.getString(R.string.check_goal_left, money(goal - total, s))
            else -> context.getString(R.string.check_goal_over, money(total - goal, s))
        }
    }

    private fun money(minor: Long, s: AppSettings) = formatter.format(minor, s.currency)

    private fun timeOf(at: Long): String =
        Instant.ofEpochMilli(at).atZone(clock.zone).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    private companion object {
        /** Older payments (a sync after a long gap, the first import) get no check. */
        val FRESH_MS = TimeUnit.HOURS.toMillis(2)
        const val MAX_SINGLE = 3
        val TWICE_MS = TimeUnit.MINUTES.toMillis(10)
        val HISTORY_MS = TimeUnit.DAYS.toMillis(180)
    }
}
