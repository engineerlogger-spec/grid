package com.grid.app.feature.spending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.Category
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriod
import com.grid.app.core.time.BudgetPeriods
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/** One category's spending in a month, with the month before for comparison. */
data class CategorySpend(
    val category: Category,
    val amountMinor: Long,
    val count: Int,
    /** Share of the month's spending, 0..1. */
    val fraction: Float,
    val previousMinor: Long,
)

data class SpendingUi(
    val loading: Boolean = true,
    val currency: String = "EUR",
    val period: BudgetPeriod? = null,
    val previousPeriod: BudgetPeriod? = null,
    val today: LocalDate = LocalDate.now(),
    val totalMinor: Long = 0,
    val previousTotalMinor: Long = 0,
    val rows: List<CategorySpend> = emptyList(),
) {
    val canGoNext: Boolean get() = period != null && period.endExclusive <= today
}

@HiltViewModel
class SpendingViewModel @Inject constructor(
    settings: SettingsRepository,
    private val transactions: TransactionRepository,
    private val clock: AppClock,
) : ViewModel() {

    /** Any date inside the shown month; starts on today. */
    private val anchor = MutableStateFlow(clock.today())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<SpendingUi> = combine(settings.settings, anchor) { s, day -> s to day }
        .flatMapLatest { (s, day) ->
            val period = BudgetPeriods.periodFor(day, s.periodStartDay)
            val previous = BudgetPeriods.previous(period, s.periodStartDay)
            combine(transactions.observePeriod(period), transactions.observePeriod(previous)) { now, before ->
                SpendingUi(
                    loading = false, currency = s.currency, period = period, previousPeriod = previous, today = clock.today(),
                    totalMinor = spending(now).sumOf { it.amountMinor },
                    previousTotalMinor = spending(before).sumOf { it.amountMinor },
                    rows = breakdown(now, before),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpendingUi())

    fun previous() {
        anchor.value = anchor.value.minusMonths(1)
    }

    fun next() {
        anchor.value = anchor.value.plusMonths(1).let { if (it.isAfter(clock.today())) clock.today() else it }
    }

    companion object {
        /** Money spent: every expense (transfers out included), never the check-in's plan figures. */
        fun spending(txs: List<Transaction>): List<Transaction> = txs.filter { it.type == TxType.EXPENSE && it.source != TxSource.CHECKIN }

        /** Categories with spending this month, biggest first, each with what it was the month before. */
        fun breakdown(current: List<Transaction>, previous: List<Transaction>): List<CategorySpend> {
            val spent = spending(current)
            val total = spent.sumOf { it.amountMinor }.takeIf { it > 0 } ?: return emptyList()
            val before = spending(previous).groupBy { it.category.id }.mapValues { (_, txs) -> txs.sumOf { it.amountMinor } }
            return spent.groupBy { it.category.id }.map { (id, txs) ->
                val amount = txs.sumOf { it.amountMinor }
                CategorySpend(txs.first().category, amount, txs.size, amount.toFloat() / total, before[id] ?: 0L)
            }.sortedByDescending { it.amountMinor }
        }
    }
}
