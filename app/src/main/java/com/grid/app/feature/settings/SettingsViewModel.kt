package com.grid.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.data.prefs.AppSettings
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.PlanRepository
import com.grid.app.core.model.ThemeMode
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriods
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(val settings: AppSettings? = null, val goalMinor: Long? = null)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val plans: PlanRepository,
    private val clock: AppClock,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<SettingsUiState> = settingsRepo.settings
        .flatMapLatest { s ->
            plans.observeGoal(BudgetPeriods.periodFor(clock.today(), s.periodStartDay)).map { SettingsUiState(s, it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    fun setDynamicColor(on: Boolean) = viewModelScope.launch { settingsRepo.setDynamicColor(on) }
    fun setCurrency(code: String) = viewModelScope.launch { settingsRepo.setCurrency(code) }
    fun setPeriodStartDay(day: Int) = viewModelScope.launch { settingsRepo.setPeriodStartDay(day) }
    fun setHideAmounts(on: Boolean) = viewModelScope.launch { settingsRepo.setHideAmounts(on) }

    fun setGoal(goalMinor: Long) = viewModelScope.launch {
        val start = settingsRepo.settings.first().periodStartDay
        plans.setGoal(BudgetPeriods.periodFor(clock.today(), start), goalMinor)
    }
}
