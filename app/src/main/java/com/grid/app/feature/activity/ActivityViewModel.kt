package com.grid.app.feature.activity

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.grid.app.R
import com.grid.app.core.bank.OwnTransfer
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.TxSource
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.Category
import com.grid.app.core.model.PaymentMethod
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriods
import com.grid.app.feature.common.title
import com.grid.app.feature.common.toLocalDate
import com.grid.app.navigation.ActivityRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

data class ActivityFilters(
    val query: String = "",
    val type: TxType? = null,
    val categoryIds: Set<Long> = emptySet(),
    val methodIds: Set<Long> = emptySet(),
    val day: LocalDate? = null,
    val allTime: Boolean = false,
) {
    val isFiltered: Boolean get() = query.isNotBlank() || type != null || categoryIds.isNotEmpty() || methodIds.isNotEmpty() || day != null || allTime
}

data class DayGroup(val date: LocalDate, val spentMinor: Long, val incomeMinor: Long, val items: List<Transaction>)

data class ActivityUiState(
    val loading: Boolean = true,
    val currency: String = "EUR",
    val periodTitle: String = "",
    val canGoNext: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val filters: ActivityFilters = ActivityFilters(),
    val groups: List<DayGroup> = emptyList(),
    val spentMinor: Long = 0,
    val incomeMinor: Long = 0,
    val categories: List<Category> = emptyList(),
    val methods: List<PaymentMethod> = emptyList(),
)

@HiltViewModel
class ActivityViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    settings: SettingsRepository,
    private val transactions: TransactionRepository,
    categories: CategoryRepository,
    bank: BankRepository,
    private val clock: AppClock,
) : ViewModel() {

    private val args = savedStateHandle.toRoute<ActivityRoute>()

    private val filters = MutableStateFlow(
        ActivityFilters(
            categoryIds = setOfNotNull(args.categoryId),
            day = args.dayEpoch?.let(LocalDate::ofEpochDay),
        ),
    )

    /** Any date inside the period being browsed; null means "the current period". */
    private val anchor = MutableStateFlow(args.dayEpoch?.let(LocalDate::ofEpochDay))

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ActivityUiState> = combine(settings.settings, anchor, filters) { s, a, f -> Triple(s, a, f) }
        .flatMapLatest { (s, a, f) ->
            val today = clock.today()
            val period = BudgetPeriods.periodFor(a ?: today, s.periodStartDay)
            val source = if (f.allTime) transactions.observeAll() else transactions.observePeriod(period)
            combine(source, bank.observeOwnTransfers(), categories.observeAll(), categories.observePaymentMethods()) { txs, moves, cats, methods ->
                // The monthly salary is a plan figure, not an entry; money moved in from own accounts counts as income.
                val entries = txs.filter { it.source != TxSource.CHECKIN } +
                    moves.filter { f.allTime || it.date in period }.map { it.toDisplayRow(s.currency) }
                val visible = entries.filter { matches(it, f) }
                ActivityUiState(
                    loading = false,
                    currency = s.currency,
                    periodTitle = period.title(today),
                    canGoNext = period.endExclusive <= today,
                    today = today,
                    filters = f,
                    groups = group(visible),
                    spentMinor = spentOf(visible),
                    incomeMinor = incomeOf(visible),
                    categories = cats.filter { !it.archived },
                    methods = methods,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState())

    fun setQuery(query: String) = filters.update { it.copy(query = query) }
    fun setType(type: TxType?) = filters.update { it.copy(type = type) }
    fun toggleCategory(id: Long) = filters.update { it.copy(categoryIds = it.categoryIds.toggle(id)) }
    fun setCategories(ids: Set<Long>) = filters.update { it.copy(categoryIds = ids) }
    fun toggleMethod(id: Long) = filters.update { it.copy(methodIds = it.methodIds.toggle(id)) }
    fun toggleAllTime() = filters.update { it.copy(allTime = !it.allTime) }
    fun clearDay() = filters.update { it.copy(day = null) }
    fun clearFilters() = filters.update { ActivityFilters() }

    fun previousPeriod() = shiftPeriod(back = true)
    fun nextPeriod() = shiftPeriod(back = false)

    fun delete(tx: Transaction, onDeleted: (Transaction) -> Unit) = viewModelScope.launch {
        transactions.delete(tx.id)?.let(onDeleted)
    }

    fun restore(tx: Transaction) = viewModelScope.launch { transactions.restore(tx) }

    /** Moving the anchor a month moves exactly one budget period, whatever the period start day. */
    private fun shiftPeriod(back: Boolean) {
        val today = clock.today()
        val current = anchor.value ?: today
        anchor.value = if (back) current.minusMonths(1) else current.plusMonths(1).let { if (it.isAfter(today)) today else it }
        filters.update { it.copy(day = null, allTime = false) }
    }

    private fun matches(tx: Transaction, f: ActivityFilters): Boolean {
        if (f.type != null && tx.type != f.type) return false
        if (f.categoryIds.isNotEmpty() && tx.category.id !in f.categoryIds) return false
        if (f.methodIds.isNotEmpty() && tx.method?.id !in f.methodIds) return false
        if (f.day != null && tx.occurredAt.toLocalDate(clock.zone) != f.day) return false
        val q = f.query.trim().lowercase(Locale.getDefault())
        if (q.isNotEmpty()) {
            val haystack = listOfNotNull(tx.merchant, tx.note, tx.category.name, tx.method?.name).joinToString(" ").lowercase(Locale.getDefault())
            if (q !in haystack) return false
        }
        return true
    }

    private fun group(txs: List<Transaction>): List<DayGroup> =
        txs.groupBy { it.occurredAt.toLocalDate(clock.zone) }
            .toSortedMap(compareByDescending { it })
            .map { (date, items) ->
                DayGroup(
                    date = date,
                    spentMinor = spentOf(items),
                    incomeMinor = incomeOf(items),
                    items = items.sortedByDescending { it.occurredAt },
                )
            }

    private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

    /** A transfer between the user's own accounts as an Activity line (negative id: not a ledger entry). */
    private fun OwnTransfer.toDisplayRow(currency: String): Transaction {
        val at = date.atTime(12, 0).atZone(clock.zone).toInstant().toEpochMilli()
        val label = context.getString(if (incoming) R.string.activity_moved_in else R.string.activity_moved_out)
        return Transaction(
            id = -id, type = if (incoming) TxType.INCOME else TxType.EXPENSE, amountMinor = amountMinor, currency = currency,
            category = Category(-1, label, "bank", "slate", if (incoming) CategoryKind.INCOME else CategoryKind.EXPENSE, 0),
            method = null, merchant = counterparty, note = null, occurredAt = at, createdAt = at, source = TxSource.BANK, ownTransfer = true,
        )
    }

    companion object {
        /** Spending only: money sent back to the user's own accounts is not spent. */
        fun spentOf(items: List<Transaction>): Long = items.filter { it.type == TxType.EXPENSE && !it.ownTransfer }.sumOf { it.amountMinor }

        /** Income plus money moved in from the user's other accounts, minus money moved back (so Net = change in balance). */
        fun incomeOf(items: List<Transaction>): Long = items.sumOf {
            when {
                it.type == TxType.INCOME -> it.amountMinor
                it.ownTransfer -> -it.amountMinor
                else -> 0L
            }
        }
    }
}
