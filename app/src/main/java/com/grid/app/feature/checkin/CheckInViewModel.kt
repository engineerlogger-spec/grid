package com.grid.app.feature.checkin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.R
import com.grid.app.core.data.db.Seed
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.PlanRepository
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriod
import com.grid.app.core.time.BudgetPeriods
import com.grid.app.feature.common.IncomeLineDraft
import com.grid.app.feature.common.moneyFieldText
import com.grid.app.feature.common.parseMoney
import com.grid.app.feature.common.title
import com.grid.app.feature.common.totalMinor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CheckInUiState(
    val loading: Boolean = true,
    val currency: String = "EUR",
    val periodTitle: String = "",
    val lines: List<IncomeLineDraft> = emptyList(),
    val goalText: String = "",
    val defaultIncomeCategoryId: Long = 0,
    val saving: Boolean = false,
    val done: Boolean = false,
) {
    val incomeMinor: Long get() = lines.totalMinor(currency)
    val goalMinor: Long? get() = parseMoney(goalText, currency)
}

@HiltViewModel
class CheckInViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val plans: PlanRepository,
    private val categories: CategoryRepository,
    private val clock: AppClock,
) : ViewModel() {

    private val _state = MutableStateFlow(CheckInUiState())
    val state: StateFlow<CheckInUiState> = _state.asStateFlow()
    private lateinit var period: BudgetPeriod

    init {
        viewModelScope.launch {
            val s = settings.settings.first()
            period = BudgetPeriods.periodFor(clock.today(), s.periodStartDay)
            val salary = categories.byIconKey(Seed.ICON_SALARY, CategoryKind.INCOME)?.id ?: 0L
            val lines = plans.incomeSources().map {
                IncomeLineDraft(name = it.name, amount = moneyFieldText(it.amountMinor, s.currency), categoryId = it.categoryId, active = it.active)
            }.ifEmpty { listOf(IncomeLineDraft(name = context.getString(R.string.checkin_salary), amount = "", categoryId = salary)) }
            _state.value = CheckInUiState(
                loading = false,
                currency = s.currency,
                periodTitle = period.title(clock.today()),
                lines = lines,
                goalText = moneyFieldText(plans.goalFor(period), s.currency),
                defaultIncomeCategoryId = salary,
            )
        }
    }

    fun setLines(lines: List<IncomeLineDraft>) = _state.update { it.copy(lines = lines) }
    fun addLine() = _state.update { it.copy(lines = it.lines + IncomeLineDraft(name = "", amount = "", categoryId = it.defaultIncomeCategoryId)) }
    fun setGoal(text: String) = _state.update { it.copy(goalText = text) }

    fun setGoalPercent(percent: Int) = _state.update {
        it.copy(goalText = moneyFieldText(it.incomeMinor * percent / 100, it.currency))
    }

    fun confirm() {
        val s = _state.value
        if (s.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val goal = s.goalMinor ?: (s.incomeMinor * 8 / 10)
            plans.confirmCheckIn(period, s.currency, goal, s.lines.map { it.toIncomeLine(s.currency) })
            _state.update { it.copy(saving = false, done = true) }
        }
    }
}
