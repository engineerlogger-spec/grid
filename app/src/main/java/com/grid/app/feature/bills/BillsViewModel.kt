package com.grid.app.feature.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.bills.ChargeState
import com.grid.app.core.bills.MonthCharge
import com.grid.app.core.bills.SubscriptionMonth
import com.grid.app.core.bills.SubscriptionPayment
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.PendingRepository
import com.grid.app.core.data.repo.SubscriptionRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.TxType
import com.grid.app.core.time.BudgetPeriods
import com.grid.app.feature.common.toLocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import com.grid.app.core.model.PendingDirection
import com.grid.app.core.model.PendingPayment
import com.grid.app.core.model.PendingStatus
import com.grid.app.core.model.Subscription
import com.grid.app.core.model.SubscriptionStatus
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BillingSchedule
import com.grid.app.core.time.todayFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class BillsUiState(
    val loading: Boolean = true,
    val currency: String = "EUR",
    val today: LocalDate = LocalDate.now(),
    val active: List<Subscription> = emptyList(),
    val inactive: List<Subscription> = emptyList(),
    /** Found in the payments by Gemini, waiting for Add / Not a bill. */
    val suggested: List<Subscription> = emptyList(),
    /** Monthly equivalent of active subscriptions in the app currency. */
    val monthlyMinor: Long = 0,
    val yearlyMinor: Long = 0,
    val toPay: List<PendingPayment> = emptyList(),
    val owedToMe: List<PendingPayment> = emptyList(),
    val settled: List<PendingPayment> = emptyList(),
    val hasReminders: Boolean = false,
    /** This month's charge of each active subscription: paid, due or late (from the bank's real payments). */
    val charges: Map<Long, MonthCharge> = emptyMap(),
) {
    val paidCount: Int get() = charges.values.count { it.state == ChargeState.PAID }
    val chargingCount: Int get() = charges.values.count { it.state != ChargeState.NONE }
    /** Still to pay this month for subscriptions in the app currency: what to keep aside. */
    val leftThisMonthMinor: Long
        get() = active.filter { it.currency == currency && charges[it.id]?.state in setOf(ChargeState.DUE, ChargeState.LATE) }.sumOf { it.amountMinor }
}

@HiltViewModel
class BillsViewModel @Inject constructor(
    settings: SettingsRepository,
    private val subscriptions: SubscriptionRepository,
    transactions: TransactionRepository,
    private val pendings: PendingRepository,
    private val clock: AppClock,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<BillsUiState> = combine(settings.settings, clock.todayFlow()) { s, today -> s to today }
        .flatMapLatest { (s, today) ->
            val period = BudgetPeriods.periodFor(today, s.periodStartDay)
            // A few days either side: a charge due on the 1st may be booked on the 30th.
            val payments = transactions.observeBetween(
                period.start.minusDays(7).atStartOfDay(clock.zone).toInstant().toEpochMilli(),
                period.endExclusive.plusDays(7).atStartOfDay(clock.zone).toInstant().toEpochMilli(),
            )
            combine(subscriptions.observeAll(), pendings.observeAll(), payments) { subs, pend, txs ->
                val active = subs.filter { it.status == SubscriptionStatus.ACTIVE }.sortedBy { it.nextCharge }
                val sameCurrency = active.filter { it.currency == s.currency }
                val recentCutoff = clock.millis() - TimeUnit.DAYS.toMillis(30)
                val open = pend.filter { it.status == PendingStatus.PENDING }
                val paid = txs.filter { it.type == TxType.EXPENSE }
                    .map { SubscriptionPayment(it.subscriptionId, it.merchant ?: it.note, it.amountMinor, it.occurredAt.toLocalDate(clock.zone)) }
                BillsUiState(
                    loading = false,
                    currency = s.currency,
                    today = today,
                    active = active,
                    inactive = subs.filter { it.status == SubscriptionStatus.PAUSED || it.status == SubscriptionStatus.CANCELLED },
                    suggested = subs.filter { it.status == SubscriptionStatus.SUGGESTED },
                    monthlyMinor = sameCurrency.sumOf { BillingSchedule.monthlyEquivalent(it.amountMinor, it.cycle) },
                    yearlyMinor = sameCurrency.sumOf { BillingSchedule.yearly(it.amountMinor, it.cycle) },
                    toPay = open.filter { it.direction == PendingDirection.I_OWE },
                    owedToMe = open.filter { it.direction == PendingDirection.OWED_TO_ME },
                    settled = pend.filter { it.status == PendingStatus.DONE && (it.settledAt ?: 0) >= recentCutoff }.sortedByDescending { it.settledAt },
                    hasReminders = active.any { it.remindDaysBefore != null } || open.any { it.remindDaysBefore != null && it.due != null },
                    charges = active.associate { it.id to SubscriptionMonth.check(it, period, today, paid) },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BillsUiState())

    fun settle(id: Long) = viewModelScope.launch { pendings.settle(id) }
    fun addSuggested(id: Long) = viewModelScope.launch { subscriptions.acceptSuggestion(id) }
    fun rejectSuggested(id: Long) = viewModelScope.launch { subscriptions.dismissDetected(id) }
    fun reopen(id: Long) = viewModelScope.launch { pendings.reopen(id) }
}
