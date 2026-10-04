package com.grid.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.bills.PastPayment
import com.grid.app.core.bills.RecurringDetector
import com.grid.app.core.bills.UpcomingItem
import com.grid.app.core.model.TxType
import com.grid.app.core.bills.UpcomingPlanner
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.ReviewKind
import com.grid.app.core.model.TxSource
import kotlinx.coroutines.flow.map
import com.grid.app.core.data.repo.CaptureRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.model.BankStatus
import com.grid.app.core.model.CaptureSource
import com.grid.app.core.data.repo.PendingRepository
import com.grid.app.core.data.repo.PlanRepository
import com.grid.app.core.data.repo.SubscriptionRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.PendingDirection
import com.grid.app.core.designsystem.components.MonthCellUi
import com.grid.app.core.insights.DashboardCalculator
import com.grid.app.core.insights.DashboardSummary
import com.grid.app.core.insights.LedgerEntry
import com.grid.app.core.model.Category
import com.grid.app.core.model.Transaction
import com.grid.app.core.time.AppClock
import com.grid.app.core.bank.MovedMoney
import com.grid.app.core.time.BudgetPeriod
import com.grid.app.core.time.BudgetPeriods
import com.grid.app.core.time.todayFlow
import com.grid.app.feature.bank.MovedViewModel
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
    /** Bills in the next 7 days (subscription charges + pending payments), overdue first. */
    val upcoming: List<UpcomingItem> = emptyList(),
    /** What the upcoming outgoing items add up to (app currency only). */
    val upcomingDueMinor: Long = 0,
    /** Detected payments waiting for a category. */
    val detectedCount: Int = 0,
    val detectedSources: List<CaptureSource> = emptyList(),
    /** The bank connection's name when its access expired and needs the user to reconnect. */
    val bankToReconnect: String? = null,
    /** Income the user entered (salary) and what was moved from it to Revolut this period; null when there's no bank sync. */
    val salaryMinor: Long = 0,
    val movedMinor: Long? = null,
    val period: BudgetPeriod? = null,
    val periodStartDay: Int = 1,
    /** Bank payments counted under "Other" that the user may want to sort. */
    val otherToSort: Int = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settings: SettingsRepository,
    transactions: TransactionRepository,
    plans: PlanRepository,
    categories: CategoryRepository,
    subscriptions: SubscriptionRepository,
    pendings: PendingRepository,
    captures: CaptureRepository,
    bank: BankRepository,
    clock: AppClock,
) : ViewModel() {

    /** Monthly payments found in the bank history (rent, phone, insurance…): Revolut shares no scheduled payments. */
    private val forecasts = combine(transactions.observeAll(), clock.todayFlow()) { txs, today ->
        val since = today.minusDays(400)
        RecurringDetector.detect(
            txs.mapNotNull { tx ->
                val date = tx.occurredAt.toLocalDate(clock.zone)
                if (tx.type != TxType.EXPENSE || tx.ownTransfer || date.isBefore(since)) return@mapNotNull null
                if (tx.source != TxSource.BANK && tx.source != TxSource.CAPTURE && tx.source != TxSource.MANUAL) return@mapNotNull null
                PastPayment(tx.merchant ?: return@mapNotNull null, tx.amountMinor, date, tx.category.iconKey, tx.category.colorKey)
            },
            today,
        )
    }

    private val upcoming = combine(subscriptions.observeAll(), pendings.observeAll(), clock.todayFlow(), forecasts, settings.settings) { subs, pend, today, expected, s ->
        UpcomingPlanner.upcoming(today, horizonDays = 7, subscriptions = subs, pendings = pend, forecasts = expected, currency = s.currency)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val base = combine(settings.settings, clock.todayFlow()) { s, today -> s to today }
        .flatMapLatest { (s, today) ->
            val period = BudgetPeriods.periodFor(today, s.periodStartDay)
            combine(
                transactions.observePeriod(period),
                plans.observeGoal(period),
                plans.observeNeedsCheckIn(period),
                transactions.observeRecent(8).map { list -> list.filter { it.source != TxSource.CHECKIN }.take(5) },
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
                    period = period,
                    periodStartDay = s.periodStartDay,
                    salaryMinor = MovedViewModel.salaryOf(periodTx),
                )
            }
        }

    private val bankState = combine(bank.observeReviewGroups(), bank.observeConnection(), bank.observeOwnTransfers()) { groups, connection, transfers ->
        Triple(groups, connection, transfers)
    }

    val state: StateFlow<HomeUiState> = combine(base, upcoming, captures.observeInbox(), bankState) { home, items, inbox, (bankGroups, connection, transfers) ->
        val period = home.period
        home.copy(
            upcoming = items,
            upcomingDueMinor = items.filter { it.direction == PendingDirection.I_OWE && it.currency == home.currency }.sumOf { it.amountMinor },
            // Notifications to confirm (and rare bank items needing a decision); payments under Other are only a link.
            detectedCount = inbox.size + bankGroups.count { it.kind != ReviewKind.CATEGORISE },
            detectedSources = (inbox.map { it.source } + if (bankGroups.any { it.kind != ReviewKind.CATEGORISE }) listOf(CaptureSource.REVOLUT) else emptyList()).distinct(),
            otherToSort = bankGroups.filter { it.kind == ReviewKind.CATEGORISE }.sumOf { it.count },
            bankToReconnect = connection?.takeIf { it.sessionId != null && it.status == BankStatus.EXPIRED }?.aspspName,
            movedMinor = if (period != null && (connection?.sessionId != null || transfers.isNotEmpty())) {
                MovedMoney.moved(transfers, period, home.periodStartDay)
            } else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

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
