package com.grid.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.PlanRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.designsystem.components.MonthCellUi
import com.grid.app.core.insights.DashboardCalculator
import com.grid.app.core.insights.DashboardSummary
import com.grid.app.core.insights.LedgerEntry
import com.grid.app.core.model.Category
import com.grid.app.core.model.Transaction
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriods
import com.grid.app.core.time.todayFlow
import com.grid.app.feature.common.title
import com.grid.app.feature.common.toLocalDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

data class CategorySlice(val category: Category, val amountMinor: Long, val fraction: Float)

data class HomeUiState(
    val loading: Boolean = true,
    val currency: String = "EUR",
    val hideAmounts: Boolean = false,
    val title: String = "",
    val today: LocalDate = LocalDate.now(),
    val summary: DashboardSummary? = null,
    val cells: List<MonthCellUi> = emptyList(),
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val topCategories: List<CategorySlice> = emptyList(),
    val recent: List<Transaction> = emptyList(),
    val needsCheckIn: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settings: SettingsRepository,
    transactions: TransactionRepository,
    plans: PlanRepository,
    categories: CategoryRepository,
    clock: AppClock,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<HomeUiState> = combine(settings.settings, clock.todayFlow()) { s, today -> s to today }
        .flatMapLatest { (s, today) ->
            val period = BudgetPeriods.periodFor(today, s.periodStartDay)
            combine(
                transactions.observePeriod(period),
                plans.observeGoal(period),
                plans.observeNeedsCheckIn(period),
                transactions.observeRecent(5),
                categories.observeAll(),
            ) { periodTx, goal, needsCheckIn, recent, cats ->
                val entries = periodTx.map { LedgerEntry(it.type, it.amountMinor, it.category.id, it.occurredAt.toLocalDate(clock.zone)) }
                val summary = DashboardCalculator.summarize(period, today, goal, entries)
                val byId = cats.associateBy { it.id }
                HomeUiState(
                    loading = false,
                    currency = s.currency,
                    hideAmounts = s.hideAmounts,
                    title = period.title(today),
                    today = today,
                    summary = summary,
                    cells = monthCells(summary),
                    firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
                    topCategories = summary.topCategories.mapNotNull { share ->
                        byId[share.categoryId]?.let { CategorySlice(it, share.amountMinor, share.fraction) }
                    },
                    recent = recent,
                    needsCheckIn = needsCheckIn && s.onboardingDone,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun toggleHideAmounts() = viewModelScope.launch { settings.setHideAmounts(!state.value.hideAmounts) }

    private fun monthCells(summary: DashboardSummary): List<MonthCellUi> {
        // Without a goal, shade days relative to the biggest day so the grid still tells a story.
        val maxDay = summary.days.maxOfOrNull { it.spentMinor }?.takeIf { it > 0 }?.toFloat()
        return summary.days.map { day ->
            if (summary.goalMinor != null) {
                MonthCellUi(day.date, day.state, intensity = if (day.ratio > 1f) (day.ratio - 1f) else day.ratio, over = day.ratio > 1f)
            } else {
                MonthCellUi(day.date, day.state, intensity = maxDay?.let { day.spentMinor / it } ?: 0f, over = false)
            }
        }
    }
}
