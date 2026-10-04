package com.grid.app.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.PlanRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.insights.InsightEntry
import com.grid.app.core.insights.InsightsEngine
import com.grid.app.core.insights.InsightsReport
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.PaymentMethod
import com.grid.app.core.model.TxSource
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriod
import com.grid.app.core.time.BudgetPeriods
import com.grid.app.feature.common.title
import com.grid.app.feature.common.toLocalDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

data class InsightsUiState(
    val loading: Boolean = true,
    val currency: String = "EUR",
    val title: String = "",
    val period: BudgetPeriod? = null,
    val today: LocalDate = LocalDate.now(),
    val canGoNext: Boolean = false,
    val report: InsightsReport? = null,
    val categories: Map<Long, Category> = emptyMap(),
    val methods: Map<Long, PaymentMethod> = emptyMap(),
    val expenseCategories: List<Category> = emptyList(),
    /** Short month labels for the history bars, oldest first. */
    val historyLabels: List<String> = emptyList(),
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    settings: SettingsRepository,
    transactions: TransactionRepository,
    plans: PlanRepository,
    private val categories: CategoryRepository,
    private val clock: AppClock,
) : ViewModel() {

    private val anchor = MutableStateFlow<LocalDate?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<InsightsUiState> = combine(settings.settings, anchor) { s, a -> s to a }
        .flatMapLatest { (s, a) ->
            val today = clock.today()
            val period = BudgetPeriods.periodFor(a ?: today, s.periodStartDay)
            val history = generateSequence(period) { BudgetPeriods.previous(it, s.periodStartDay) }.take(6).toList().reversed()
            combine(
                transactions.observeBetween(history.first().startMillis(clock.zone), period.endMillis(clock.zone)),
                plans.observeGoal(period),
                categories.observeAll(),
                categories.observePaymentMethods(),
            ) { txs, goal, cats, methods ->
                val entries = txs.filter { it.currency == s.currency }.map {
                    InsightEntry(
                        it.type, it.amountMinor, it.category.id, it.method?.id, it.title, it.occurredAt.toLocalDate(clock.zone),
                        isSubscription = it.source == TxSource.SUBSCRIPTION,
                        isFixed = it.source == TxSource.SUBSCRIPTION || it.source == TxSource.PENDING,
                    )
                }
                val limits = cats.filter { it.kind == CategoryKind.EXPENSE && !it.archived && (it.monthlyLimitMinor ?: 0) > 0 }
                    .associate { it.id to it.monthlyLimitMinor!! }
                InsightsUiState(
                    loading = false,
                    currency = s.currency,
                    title = period.title(today),
                    period = period,
                    today = today,
                    canGoNext = period.endExclusive <= today,
                    report = InsightsEngine.compute(period, today, goal, entries, history, limits),
                    categories = cats.associateBy { it.id },
                    methods = methods.associateBy { it.id },
                    expenseCategories = cats.filter { it.kind == CategoryKind.EXPENSE && !it.archived },
                    historyLabels = history.map { it.labelMonth.month.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()).take(3) },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    fun previous() { anchor.value = (anchor.value ?: clock.today()).minusMonths(1) }

    fun next() {
        val today = clock.today()
        anchor.value = (anchor.value ?: today).plusMonths(1).let { if (it.isAfter(today)) null else it }
    }

    fun setBudget(categoryId: Long, limitMinor: Long?) = viewModelScope.launch { categories.setMonthlyLimit(categoryId, limitMinor) }
}
