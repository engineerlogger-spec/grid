package com.grid.app.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.grid.app.core.model.ThemeMode
import com.grid.app.core.money.Currencies
import com.grid.app.core.time.BudgetPeriods
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale

data class AppSettings(
    val currency: String,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    /** Day of month a budget period starts (1–28), e.g. 25 for people paid on the 25th. */
    val periodStartDay: Int = 1,
    val onboardingDone: Boolean = false,
    val hideAmounts: Boolean = false,
    val appLock: Boolean = false,
)

/** User preferences in DataStore. Ledger data lives in Room; this is configuration only. */
class SettingsRepository(
    private val store: DataStore<Preferences>,
    private val locale: Locale = Locale.getDefault(),
) {
    private object Keys {
        val currency = stringPreferencesKey("currency")
        val theme = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val periodStartDay = intPreferencesKey("period_start_day")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val hideAmounts = booleanPreferencesKey("hide_amounts")
        val appLock = booleanPreferencesKey("app_lock")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            currency = p[Keys.currency] ?: Currencies.defaultFor(locale),
            themeMode = p[Keys.theme]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.dynamicColor] ?: false,
            periodStartDay = p[Keys.periodStartDay] ?: 1,
            onboardingDone = p[Keys.onboardingDone] ?: false,
            hideAmounts = p[Keys.hideAmounts] ?: false,
            appLock = p[Keys.appLock] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun setCurrency(code: String) = store.edit { it[Keys.currency] = code }
    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = store.edit { it[Keys.dynamicColor] = enabled }
    suspend fun setPeriodStartDay(day: Int) = store.edit {
        it[Keys.periodStartDay] = day.coerceIn(BudgetPeriods.MIN_START_DAY, BudgetPeriods.MAX_START_DAY)
    }
    suspend fun completeOnboarding() = store.edit { it[Keys.onboardingDone] = true }
    suspend fun setHideAmounts(hidden: Boolean) = store.edit { it[Keys.hideAmounts] = hidden }
    suspend fun setAppLock(enabled: Boolean) = store.edit { it[Keys.appLock] = enabled }
}
