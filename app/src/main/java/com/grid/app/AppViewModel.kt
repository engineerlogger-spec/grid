package com.grid.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.data.prefs.AppSettings
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.PlanRepository
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriods
import com.grid.app.core.time.todayFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface AppUiState {
    data object Loading : AppUiState
    data class Ready(val settings: AppSettings, val needsCheckIn: Boolean) : AppUiState
}

/** App-level state: settings (theme, onboarding) and whether this month's income check-in is due. */
@HiltViewModel
class AppViewModel @Inject constructor(
    settings: SettingsRepository,
    plans: PlanRepository,
    clock: AppClock,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<AppUiState> = combine(settings.settings, clock.todayFlow()) { s, today -> s to today }
        .flatMapLatest { (s, today) ->
            if (!s.onboardingDone) flowOf(AppUiState.Ready(s, needsCheckIn = false))
            else plans.observeNeedsCheckIn(BudgetPeriods.periodFor(today, s.periodStartDay)).map { AppUiState.Ready(s, it) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState.Loading)

    private val _checkInDismissed = MutableStateFlow(false)
    /** "Later" on the check-in hides it until the next app start (Home keeps a banner). */
    val checkInDismissed: StateFlow<Boolean> = _checkInDismissed.asStateFlow()

    fun dismissCheckIn() { _checkInDismissed.value = true }
}
