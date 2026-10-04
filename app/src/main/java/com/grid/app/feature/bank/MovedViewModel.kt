package com.grid.app.feature.bank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.bank.MovedMoney
import com.grid.app.core.bank.OwnTransfer
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.TransactionRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** One transfer as listed for a month, with where it is counted. */
data class MovedRow(val transfer: OwnTransfer, val countedHere: Boolean, val datedHere: Boolean)

data class MovedUi(
    val loading: Boolean = true,
    val currency: String = "EUR",
    val period: BudgetPeriod? = null,
    val today: LocalDate = LocalDate.now(),
    /** Income the user entered themselves (check-in salary, manual income): the money that arrives in the salary account. */
    val salaryMinor: Long = 0,
    val movedMinor: Long = 0,
    val rows: List<MovedRow> = emptyList(),
) {
    val savedMinor: Long get() = salaryMinor - movedMinor
}

@HiltViewModel
class MovedViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val bank: BankRepository,
    private val transactions: TransactionRepository,
    private val clock: AppClock,
) : ViewModel() {

    /** Any date inside the shown period; starts on today. */
    private val anchor = MutableStateFlow(clock.today())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<MovedUi> = combine(settings.settings, anchor) { s, day -> s to BudgetPeriods.periodFor(day, s.periodStartDay) }
        .flatMapLatest { (s, period) ->
            combine(bank.observeOwnTransfers(), transactions.observePeriod(period)) { transfers, periodTx ->
                MovedUi(
                    loading = false, currency = s.currency, period = period, today = clock.today(),
                    salaryMinor = salaryOf(periodTx),
                    movedMinor = MovedMoney.moved(transfers, period, s.periodStartDay),
                    rows = MovedMoney.shownIn(transfers, period, s.periodStartDay).map { t ->
                        MovedRow(t, countedHere = MovedMoney.periodOf(t, s.periodStartDay) == period, datedHere = t.date in period)
                    },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MovedUi())

    fun previous() = shift(-1)
    fun next() = shift(1)

    private fun shift(by: Int) = viewModelScope.launch {
        val start = settings.settings.first().periodStartDay
        val period = BudgetPeriods.periodFor(anchor.value, start)
        anchor.value = if (by < 0) BudgetPeriods.previous(period, start).start else BudgetPeriods.next(period, start).start
    }

    /** Ticked: count this transfer in the month after its own; unticked: back in its own month. */
    fun setNextMonth(row: MovedRow, nextMonth: Boolean) = viewModelScope.launch {
        val start = settings.settings.first().periodStartDay
        val own = BudgetPeriods.periodFor(row.transfer.date, start)
        bank.countIn(row.transfer.id, if (nextMonth) BudgetPeriods.next(own, start).start else null)
    }

    companion object {
        fun salaryOf(periodTx: List<Transaction>): Long =
            periodTx.filter { it.type == TxType.INCOME && (it.source == TxSource.CHECKIN || it.source == TxSource.MANUAL) }.sumOf { it.amountMinor }
    }
}
