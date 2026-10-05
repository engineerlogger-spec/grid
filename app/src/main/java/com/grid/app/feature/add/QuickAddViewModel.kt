package com.grid.app.feature.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.PaymentMethod
import com.grid.app.core.model.QuickSuggestion
import com.grid.app.core.model.Transaction
import com.grid.app.core.model.TransactionDraft
import com.grid.app.core.model.TxSource
import com.grid.app.core.model.TxType
import com.grid.app.core.money.AmountInput
import com.grid.app.core.money.Currencies
import com.grid.app.core.money.KeypadKey
import com.grid.app.core.time.AppClock
import com.grid.app.feature.common.QuickAddRequest
import com.grid.app.feature.common.moneyFieldText
import com.grid.app.feature.common.parseMoney
import com.grid.app.feature.common.toLocalDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

enum class QuickAddError { ENTER_AMOUNT, PICK_CATEGORY }

data class QuickAddUiState(
    val loading: Boolean = true,
    val type: TxType = TxType.EXPENSE,
    val currency: String = "EUR",
    val input: AmountInput = AmountInput(),
    /** Active categories of [type], most used first. */
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: Long? = null,
    val methods: List<PaymentMethod> = emptyList(),
    val methodId: Long? = null,
    val date: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val note: String = "",
    val noteOpen: Boolean = false,
    val suggestions: List<QuickSuggestion> = emptyList(),
    val editing: Transaction? = null,
    val showAllCategories: Boolean = false,
    val error: QuickAddError? = null,
    /** Incremented to replay the shake animation. */
    val shake: Int = 0,
    /** Editing: the amount as typed with the system keyboard (the calculator keypad is for quick adding). */
    val amountText: String = "",
    /** Editing: other entries with the same payee, and whether a new category applies to them too. */
    val similarCount: Int = 0,
    val applyToAll: Boolean = true,
) {
    val isEditing: Boolean get() = editing != null
    val method: PaymentMethod? get() = methods.firstOrNull { it.id == methodId }
    val amountMinor: Long? get() = if (isEditing) parseMoney(amountText, currency) else input.valueMinor
    /** Ask "only this / all similar" once the category is changed on an entry whose payee has other entries. */
    val asksScope: Boolean get() = editing != null && similarCount > 0 && selectedCategoryId != editing.category.id
}

sealed interface QuickAddEvent {
    data class Saved(val txId: Long, val amountMinor: Long, val currency: String, val categoryName: String, val wasEdit: Boolean) : QuickAddEvent
    data class Deleted(val tx: Transaction) : QuickAddEvent
}

/**
 * State for the quick-add sheet. Activity-scoped (the sheet lives at the app root) and reset by [open].
 * The fast path: type an amount, tap a category → saved.
 */
@HiltViewModel
class QuickAddViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val clock: AppClock,
) : ViewModel() {

    private val _state = MutableStateFlow(QuickAddUiState())
    val state: StateFlow<QuickAddUiState> = _state.asStateFlow()

    private val _events = Channel<QuickAddEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var openedToken: Long? = null

    fun open(request: QuickAddRequest) {
        if (request.token == openedToken) return
        openedToken = request.token
        _state.value = QuickAddUiState(loading = true, type = request.type, date = clock.today(), today = clock.today())
        viewModelScope.launch {
            val currency = settings.settings.first().currency
            val methods = categories.observePaymentMethods().first()
            val editing = request.editId?.let { transactions.get(it) }
            val type = editing?.type ?: request.type
            val digits = Currencies.fractionDigits(editing?.currency ?: currency)
            _state.value = QuickAddUiState(
                loading = false,
                type = type,
                currency = editing?.currency ?: currency,
                input = editing?.let { AmountInput.fromMinor(it.amountMinor, digits) } ?: AmountInput(fractionDigits = digits),
                categories = orderedCategories(type),
                selectedCategoryId = editing?.category?.id,
                methods = methods,
                methodId = editing?.method?.id ?: transactions.lastPaymentMethodId()?.takeIf { id -> methods.any { it.id == id } },
                date = editing?.occurredAt?.toLocalDate(clock.zone) ?: clock.today(),
                today = clock.today(),
                note = editing?.note.orEmpty(),
                noteOpen = !editing?.note.isNullOrBlank(),
                suggestions = if (editing == null && type == TxType.EXPENSE) transactions.suggestions() else emptyList(),
                editing = editing,
                similarCount = editing?.let { transactions.samePayeeIds(it).size } ?: 0,
                amountText = editing?.let { moneyFieldText(it.amountMinor, it.currency) }.orEmpty(),
            )
        }
    }

    fun setType(type: TxType) {
        if (type == _state.value.type) return
        viewModelScope.launch {
            val ordered = orderedCategories(type)
            _state.update {
                it.copy(
                    type = type, categories = ordered, selectedCategoryId = null, showAllCategories = false, error = null,
                    suggestions = if (type == TxType.EXPENSE && !it.isEditing) transactions.suggestions() else emptyList(),
                )
            }
        }
    }

    fun press(key: KeypadKey) = _state.update { it.copy(input = it.input.press(key), error = null) }
    fun setAmountText(text: String) = _state.update { it.copy(amountText = text, error = null) }
    fun setApplyToAll(all: Boolean) = _state.update { it.copy(applyToAll = all) }

    /** Fast path: with an amount typed, tapping a category saves immediately. */
    fun tapCategory(category: Category) {
        val s = _state.value
        if (s.isEditing) {
            _state.update { it.copy(selectedCategoryId = category.id, error = null) }
            return
        }
        val amount = s.input.valueMinor
        if (amount == null || amount <= 0) {
            _state.update { it.copy(selectedCategoryId = category.id, error = QuickAddError.ENTER_AMOUNT, shake = it.shake + 1) }
            return
        }
        save(category.id, amount)
    }

    fun longPressCategory(category: Category) = _state.update { it.copy(selectedCategoryId = category.id, error = null) }

    fun saveSelected() {
        val s = _state.value
        val amount = s.amountMinor
        when {
            amount == null || amount <= 0 -> _state.update { it.copy(error = QuickAddError.ENTER_AMOUNT, shake = it.shake + 1) }
            s.selectedCategoryId == null -> _state.update { it.copy(error = QuickAddError.PICK_CATEGORY, shake = it.shake + 1) }
            else -> save(s.selectedCategoryId, amount)
        }
    }

    fun applySuggestion(suggestion: QuickSuggestion) =
        save(suggestion.category.id, suggestion.amountMinor, note = suggestion.label, methodId = suggestion.paymentMethodId ?: _state.value.methodId)

    fun toggleShowAll() = _state.update { it.copy(showAllCategories = !it.showAllCategories) }
    fun setMethod(id: Long?) = _state.update { it.copy(methodId = id) }
    fun setDate(date: LocalDate) = _state.update { it.copy(date = date) }
    fun setNote(note: String) = _state.update { it.copy(note = note) }
    fun toggleNote() = _state.update { it.copy(noteOpen = !it.noteOpen) }

    fun delete() {
        val editing = _state.value.editing ?: return
        viewModelScope.launch {
            transactions.delete(editing.id)?.let { _events.send(QuickAddEvent.Deleted(it)) }
        }
    }

    fun undoSave(txId: Long) = viewModelScope.launch { transactions.delete(txId) }
    fun undoDelete(tx: Transaction) = viewModelScope.launch { transactions.restore(tx) }

    private fun save(categoryId: Long, amountMinor: Long, note: String = _state.value.note, methodId: Long? = _state.value.methodId) {
        val s = _state.value
        val category = s.categories.firstOrNull { it.id == categoryId }
        val occurredAt = occurredAtFor(s)
        val editing = s.editing
        val draft = TransactionDraft(
            type = s.type,
            amountMinor = amountMinor,
            currency = s.currency,
            categoryId = categoryId,
            paymentMethodId = methodId,
            // The sheet edits the note; a captured payment's merchant is carried over untouched.
            merchant = editing?.merchant,
            note = note,
            occurredAt = occurredAt,
            source = editing?.source ?: TxSource.MANUAL,
            subscriptionId = editing?.subscriptionId,
            pendingId = editing?.pendingId,
            captureId = editing?.captureId,
        )
        viewModelScope.launch {
            val id = if (editing != null) {
                transactions.update(editing.id, draft, samePayeeToo = s.asksScope && s.applyToAll)
                editing.id
            } else {
                transactions.add(draft)
            }
            _events.send(QuickAddEvent.Saved(id, amountMinor, s.currency, category?.name.orEmpty(), wasEdit = editing != null))
        }
    }

    /** Keeps the original time when the date is unchanged; "now" for today; midday for other days. */
    private fun occurredAtFor(s: QuickAddUiState): Long {
        val editing = s.editing
        if (editing != null && editing.occurredAt.toLocalDate(clock.zone) == s.date) return editing.occurredAt
        return if (s.date == clock.today()) clock.millis() else s.date.atTime(12, 0).atZone(clock.zone).toInstant().toEpochMilli()
    }

    private suspend fun orderedCategories(type: TxType): List<Category> {
        val kind = if (type == TxType.EXPENSE) CategoryKind.EXPENSE else CategoryKind.INCOME
        return categories.orderedByUsage(kind, transactions.categoryUsage(type))
    }
}
