package com.grid.app.feature.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.R
import com.grid.app.core.data.db.Seed
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.PlanRepository
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.ThemeMode
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriods
import com.grid.app.feature.common.IncomeLineDraft
import com.grid.app.feature.common.parseMoney
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

enum class OnboardingPage { WELCOME, CURRENCY, INCOME, GOAL, THEME, READY }

data class OnboardingUiState(
    val page: OnboardingPage = OnboardingPage.WELCOME,
    val currency: String = "EUR",
    val lines: List<IncomeLineDraft> = emptyList(),
    /** Goal as a share of income (70/80/90), or null for a custom amount in [customGoal]. */
    val goalPercent: Int? = 80,
    val customGoal: String = "",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val defaultIncomeCategoryId: Long = 0,
    val finishing: Boolean = false,
) {
    val incomeMinor: Long get() = lines.totalMinor(currency)
    val goalMinor: Long get() = goalPercent?.let { incomeMinor * it / 100 } ?: (parseMoney(customGoal, currency) ?: 0L)
    val canContinue: Boolean
        get() = when (page) {
            OnboardingPage.INCOME -> incomeMinor > 0
            OnboardingPage.GOAL -> goalMinor > 0
            else -> true
        }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val plans: PlanRepository,
    private val categories: CategoryRepository,
    private val clock: AppClock,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = settings.settings.first()
            val salary = categories.byIconKey(Seed.ICON_SALARY, CategoryKind.INCOME)?.id ?: 0L
            _state.update {
                it.copy(
                    currency = s.currency,
                    theme = s.themeMode,
                    defaultIncomeCategoryId = salary,
                    lines = listOf(IncomeLineDraft(name = context.getString(R.string.checkin_salary), amount = "", categoryId = salary)),
                )
            }
        }
    }

    fun next() = _state.update { s -> s.copy(page = OnboardingPage.entries.getOrElse(s.page.ordinal + 1) { s.page }) }
    fun back() = _state.update { s -> s.copy(page = OnboardingPage.entries.getOrElse(s.page.ordinal - 1) { s.page }) }

    fun setCurrency(code: String) = _state.update { it.copy(currency = code) }
    fun setLines(lines: List<IncomeLineDraft>) = _state.update { it.copy(lines = lines) }
    fun addLine() = _state.update { it.copy(lines = it.lines + IncomeLineDraft(name = "", amount = "", categoryId = it.defaultIncomeCategoryId)) }
    fun setGoalPercent(percent: Int) = _state.update { it.copy(goalPercent = percent) }
    fun setCustomGoal(text: String) = _state.update { it.copy(goalPercent = null, customGoal = text) }

    /** Applies immediately so the user previews the theme on the onboarding screen itself. */
    fun setTheme(mode: ThemeMode) {
        _state.update { it.copy(theme = mode) }
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun finish() {
        val s = _state.value
        if (s.finishing) return
        _state.update { it.copy(finishing = true) }
        viewModelScope.launch {
            settings.setCurrency(s.currency)
            val period = BudgetPeriods.periodFor(clock.today(), settings.settings.first().periodStartDay)
            plans.confirmCheckIn(period, s.currency, s.goalMinor, s.lines.map { it.toIncomeLine(s.currency) })
            settings.completeOnboarding()
        }
    }
}
