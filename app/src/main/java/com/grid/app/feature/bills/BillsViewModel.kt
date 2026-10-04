package com.grid.app.feature.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.PendingRepository
import com.grid.app.core.data.repo.SubscriptionRepository
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
    /** Monthly equivalent of active subscriptions in the app currency. */
    val monthlyMinor: Long = 0,
    val yearlyMinor: Long = 0,
    val toPay: List<PendingPayment> = emptyList(),
    val owedToMe: List<PendingPayment> = emptyList(),
    val settled: List<PendingPayment> = emptyList(),
    val hasReminders: Boolean = false,
)

@HiltViewModel
class BillsViewModel @Inject constructor(
    settings: SettingsRepository,
    subscriptions: SubscriptionRepository,
    private val pendings: PendingRepository,
    private val clock: AppClock,
) : ViewModel() {

    val state: StateFlow<BillsUiState> = combine(
        settings.settings, subscriptions.observeAll(), pendings.observeAll(), clock.todayFlow(),
    ) { s, subs, pend, today ->
        val active = subs.filter { it.status == SubscriptionStatus.ACTIVE }.sortedBy { it.nextCharge }
        val sameCurrency = active.filter { it.currency == s.currency }
        val recentCutoff = clock.millis() - TimeUnit.DAYS.toMillis(30)
        val open = pend.filter { it.status == PendingStatus.PENDING }
        BillsUiState(
            loading = false,
            currency = s.currency,
            today = today,
            active = active,
            inactive = subs.filter { it.status != SubscriptionStatus.ACTIVE },
            monthlyMinor = sameCurrency.sumOf { BillingSchedule.monthlyEquivalent(it.amountMinor, it.cycle) },
            yearlyMinor = sameCurrency.sumOf { BillingSchedule.yearly(it.amountMinor, it.cycle) },
            toPay = open.filter { it.direction == PendingDirection.I_OWE },
            owedToMe = open.filter { it.direction == PendingDirection.OWED_TO_ME },
            settled = pend.filter { it.status == PendingStatus.DONE && (it.settledAt ?: 0) >= recentCutoff }.sortedByDescending { it.settledAt },
            hasReminders = active.any { it.remindDaysBefore != null } || open.any { it.remindDaysBefore != null && it.due != null },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BillsUiState())

    fun settle(id: Long) = viewModelScope.launch { pendings.settle(id) }
    fun reopen(id: Long) = viewModelScope.launch { pendings.reopen(id) }
}
