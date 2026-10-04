package com.grid.app.feature.bills

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.PendingRepository
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.PendingDirection
import com.grid.app.core.model.PendingDraft
import com.grid.app.core.model.PendingStatus
import com.grid.app.core.time.AppClock
import com.grid.app.core.work.WorkScheduler
import com.grid.app.feature.common.moneyFieldText
import com.grid.app.feature.common.parseMoney
import com.grid.app.navigation.PendingEditRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class PendingEditorState(
    val loading: Boolean = true,
    val id: Long? = null,
    val direction: PendingDirection = PendingDirection.I_OWE,
    val title: String = "",
    val counterparty: String = "",
    val amount: String = "",
    val currency: String = "EUR",
    val due: LocalDate? = null,
    val today: LocalDate = LocalDate.now(),
    val expenseCategories: List<Category> = emptyList(),
    val incomeCategories: List<Category> = emptyList(),
    val categoryId: Long? = null,
    val note: String = "",
    val remindDays: Int? = 1,
    val status: PendingStatus = PendingStatus.PENDING,
    val showErrors: Boolean = false,
    val done: Boolean = false,
) {
    val amountMinor: Long? get() = parseMoney(amount, currency)?.takeIf { it > 0 }
    val categories: List<Category> get() = if (direction == PendingDirection.I_OWE) expenseCategories else incomeCategories
    val isNew: Boolean get() = id == null
}

@HiltViewModel
class PendingEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val pendings: PendingRepository,
    private val categoryRepo: CategoryRepository,
    private val settings: SettingsRepository,
    private val clock: AppClock,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<PendingEditRoute>()
    private val _state = MutableStateFlow(PendingEditorState())
    val state: StateFlow<PendingEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val currency = settings.settings.first().currency
            val expense = categoryRepo.observeActive(CategoryKind.EXPENSE).first()
            val income = categoryRepo.observeActive(CategoryKind.INCOME).first()
            val existing = route.id?.let { pendings.get(it) }
            _state.value = PendingEditorState(
                loading = false,
                id = existing?.id,
                direction = existing?.direction ?: PendingDirection.I_OWE,
                title = existing?.title.orEmpty(),
                counterparty = existing?.counterparty.orEmpty(),
                amount = existing?.let { moneyFieldText(it.amountMinor, it.currency) }.orEmpty(),
                currency = existing?.currency ?: currency,
                due = existing?.due ?: clock.today().plusDays(7),
                today = clock.today(),
                expenseCategories = expense,
                incomeCategories = income,
                categoryId = existing?.category?.id,
                note = existing?.note.orEmpty(),
                remindDays = existing?.remindDaysBefore ?: if (existing == null) 1 else null,
                status = existing?.status ?: PendingStatus.PENDING,
            )
        }
    }

    fun setDirection(v: PendingDirection) = _state.update { it.copy(direction = v, categoryId = null) }
    fun setTitle(v: String) = _state.update { it.copy(title = v) }
    fun setCounterparty(v: String) = _state.update { it.copy(counterparty = v) }
    fun setAmount(v: String) = _state.update { it.copy(amount = v) }
    fun setDue(v: LocalDate?) = _state.update { it.copy(due = v) }
    fun setCategory(id: Long?) = _state.update { it.copy(categoryId = id) }
    fun setNote(v: String) = _state.update { it.copy(note = v) }
    fun setRemind(v: Int?) = _state.update { it.copy(remindDays = v) }

    fun save() {
        val s = _state.value
        val amount = s.amountMinor
        if (s.title.isBlank() || amount == null) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        val draft = PendingDraft(
            title = s.title, counterparty = s.counterparty, direction = s.direction, amountMinor = amount, currency = s.currency,
            due = s.due, categoryId = s.categoryId, note = s.note, remindDaysBefore = s.remindDays,
        )
        viewModelScope.launch {
            if (s.id == null) pendings.add(draft) else pendings.update(s.id, draft)
            WorkScheduler.runNow(context)
            _state.update { it.copy(done = true) }
        }
    }

    fun reopen() {
        val id = _state.value.id ?: return
        viewModelScope.launch { pendings.reopen(id); _state.update { it.copy(done = true) } }
    }

    fun delete() {
        val id = _state.value.id ?: return
        viewModelScope.launch { pendings.delete(id); _state.update { it.copy(done = true) } }
    }
}
