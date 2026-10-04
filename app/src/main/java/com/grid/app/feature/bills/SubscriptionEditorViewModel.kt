package com.grid.app.feature.bills

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.SubscriptionRepository
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.Cycle
import com.grid.app.core.model.CycleUnit
import com.grid.app.core.model.PaymentMethod
import com.grid.app.core.model.SubscriptionDraft
import com.grid.app.core.model.SubscriptionStatus
import com.grid.app.core.time.AppClock
import com.grid.app.core.work.WorkScheduler
import com.grid.app.feature.common.moneyFieldText
import com.grid.app.feature.common.parseMoney
import com.grid.app.navigation.SubscriptionEditRoute
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

enum class CycleChoice(val cycle: Cycle?) {
    WEEKLY(Cycle(CycleUnit.WEEK, 1)),
    MONTHLY(Cycle.Monthly),
    QUARTERLY(Cycle(CycleUnit.MONTH, 3)),
    YEARLY(Cycle(CycleUnit.YEAR, 1)),
    CUSTOM(null);

    companion object {
        fun of(cycle: Cycle): CycleChoice = entries.firstOrNull { it.cycle == cycle } ?: CUSTOM
    }
}

data class SubscriptionEditorState(
    val loading: Boolean = true,
    val id: Long? = null,
    val name: String = "",
    val amount: String = "",
    val currency: String = "EUR",
    val choice: CycleChoice = CycleChoice.MONTHLY,
    val customUnit: CycleUnit = CycleUnit.MONTH,
    val customCount: Int = 2,
    val nextCharge: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val categories: List<Category> = emptyList(),
    val categoryId: Long? = null,
    val methods: List<PaymentMethod> = emptyList(),
    val methodId: Long? = null,
    val remindDays: Int? = 1,
    val autoLog: Boolean = true,
    val colorKey: String = "violet",
    val note: String = "",
    val status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    val showErrors: Boolean = false,
    val done: Boolean = false,
) {
    val cycle: Cycle get() = choice.cycle ?: Cycle(customUnit, customCount.coerceAtLeast(1))
    val amountMinor: Long? get() = parseMoney(amount, currency)?.takeIf { it > 0 }
    val nameError: Boolean get() = showErrors && name.isBlank()
    val amountError: Boolean get() = showErrors && amountMinor == null
    val isNew: Boolean get() = id == null
}

@HiltViewModel
class SubscriptionEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val subscriptions: SubscriptionRepository,
    private val categoryRepo: CategoryRepository,
    private val settings: SettingsRepository,
    private val clock: AppClock,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<SubscriptionEditRoute>()
    private val _state = MutableStateFlow(SubscriptionEditorState())
    val state: StateFlow<SubscriptionEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val currency = settings.settings.first().currency
            val categories = categoryRepo.observeActive(CategoryKind.EXPENSE).first()
            val methods = categoryRepo.observePaymentMethods().first()
            val existing = route.id?.let { subscriptions.get(it) }
            val defaultCategory = categories.firstOrNull { it.iconKey == "subscriptions" }?.id ?: categories.firstOrNull()?.id
            _state.value = if (existing != null) {
                SubscriptionEditorState(
                    loading = false, id = existing.id, name = existing.name, amount = moneyFieldText(existing.amountMinor, existing.currency),
                    currency = existing.currency, choice = CycleChoice.of(existing.cycle), customUnit = existing.cycle.unit, customCount = existing.cycle.count,
                    nextCharge = existing.nextCharge, today = clock.today(), categories = categories, categoryId = existing.category.id,
                    methods = methods, methodId = existing.paymentMethodId, remindDays = existing.remindDaysBefore, autoLog = existing.autoLog,
                    colorKey = existing.colorKey, note = existing.note.orEmpty(), status = existing.status,
                )
            } else {
                SubscriptionEditorState(
                    loading = false, currency = currency, nextCharge = clock.today(), today = clock.today(),
                    categories = categories, categoryId = defaultCategory, methods = methods,
                )
            }
        }
    }

    fun applyPreset(preset: SubscriptionPreset) = _state.update { s ->
        s.copy(
            name = preset.name, colorKey = preset.colorKey, choice = CycleChoice.of(preset.cycle),
            categoryId = s.categories.firstOrNull { it.iconKey == preset.categoryIcon }?.id ?: s.categoryId,
        )
    }

    fun setName(v: String) = _state.update { it.copy(name = v) }
    fun setAmount(v: String) = _state.update { it.copy(amount = v) }
    fun setChoice(v: CycleChoice) = _state.update { it.copy(choice = v) }
    fun setCustomUnit(v: CycleUnit) = _state.update { it.copy(customUnit = v) }
    fun setCustomCount(v: Int) = _state.update { it.copy(customCount = v.coerceIn(1, 99)) }
    fun setNextCharge(v: LocalDate) = _state.update { it.copy(nextCharge = v) }
    fun setCategory(id: Long?) = _state.update { it.copy(categoryId = id ?: it.categoryId) }
    fun setMethod(id: Long?) = _state.update { it.copy(methodId = id) }
    fun setRemind(days: Int?) = _state.update { it.copy(remindDays = days) }
    fun setAutoLog(on: Boolean) = _state.update { it.copy(autoLog = on) }
    fun setColor(key: String) = _state.update { it.copy(colorKey = key) }
    fun setNote(v: String) = _state.update { it.copy(note = v) }

    fun save() {
        val s = _state.value
        val amount = s.amountMinor
        val categoryId = s.categoryId
        if (s.name.isBlank() || amount == null || categoryId == null) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        val draft = SubscriptionDraft(
            name = s.name, amountMinor = amount, currency = s.currency, cycle = s.cycle, nextCharge = s.nextCharge,
            categoryId = categoryId, paymentMethodId = s.methodId, remindDaysBefore = s.remindDays, autoLog = s.autoLog,
            colorKey = s.colorKey, note = s.note,
        )
        viewModelScope.launch {
            if (s.id == null) subscriptions.add(draft) else subscriptions.update(s.id, draft)
            WorkScheduler.runNow(context) // reminders for the new schedule
            _state.update { it.copy(done = true) }
        }
    }

    fun setStatus(status: SubscriptionStatus) {
        val id = _state.value.id ?: return
        viewModelScope.launch {
            subscriptions.setStatus(id, status)
            _state.update { it.copy(done = true) }
        }
    }

    fun delete() {
        val id = _state.value.id ?: return
        viewModelScope.launch {
            subscriptions.delete(id)
            _state.update { it.copy(done = true) }
        }
    }
}
