package com.grid.app.feature.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.bank.BankSync
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.DeletedEntry
import com.grid.app.core.model.TxType
import com.grid.app.core.time.AppClock
import com.grid.app.feature.common.toLocalDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** A bank payment whose entry was deleted before "Recently deleted" kept copies: it can be booked again. */
data class EarlierPayment(val rowId: Long, val title: String, val type: TxType, val amountMinor: Long, val currency: String, val date: LocalDate)

data class RecentlyDeletedUi(
    val loading: Boolean = true,
    val deleted: List<DeletedEntry> = emptyList(),
    val earlier: List<EarlierPayment> = emptyList(),
    val today: LocalDate = LocalDate.now(),
) {
    val isEmpty: Boolean get() = deleted.isEmpty() && earlier.isEmpty()
}

@HiltViewModel
class RecentlyDeletedViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    private val bank: BankRepository,
    private val bankSync: BankSync,
    settings: SettingsRepository,
    private val clock: AppClock,
) : ViewModel() {

    init {
        // An entry an older Undo put back is still there: re-link it rather than offer it as deleted.
        viewModelScope.launch { bank.relinkOrphans() }
    }

    val state: StateFlow<RecentlyDeletedUi> = combine(transactions.observeDeleted(), bank.observeOrphans(), settings.settings) { deleted, orphans, s ->
        RecentlyDeletedUi(
            loading = false,
            deleted = deleted,
            earlier = orphans.filter { it.currency == s.currency }.map { row ->
                EarlierPayment(
                    rowId = row.id, title = BankRepository.displayName(row) ?: "?",
                    type = if (row.direction == CaptureDirection.OUT) TxType.EXPENSE else TxType.INCOME,
                    amountMinor = row.amountMinor, currency = row.currency, date = row.occurredAt.toLocalDate(clock.zone),
                )
            },
            today = clock.today(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentlyDeletedUi())

    fun restore(entry: DeletedEntry) = viewModelScope.launch { transactions.restoreDeleted(entry.tx.id) }

    fun restore(payment: EarlierPayment) = viewModelScope.launch { bankSync.rebook(payment.rowId) }
}
