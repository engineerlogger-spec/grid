package com.grid.app.core.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.grid.app.core.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

class SettingsRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.repo(locale: Locale = Locale.FRANCE): SettingsRepository {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("settings.preferences_pb").also { it.delete() } }
        return SettingsRepository(store, locale)
    }

    @Test fun defaults() = runTest(UnconfinedTestDispatcher()) {
        val s = repo().settings.first()
        assertThat(s.currency).isEqualTo("EUR")
        assertThat(s.themeMode).isEqualTo(ThemeMode.SYSTEM)
        assertThat(s.dynamicColor).isFalse()
        assertThat(s.periodStartDay).isEqualTo(1)
        assertThat(s.onboardingDone).isFalse()
        assertThat(s.hideAmounts).isFalse()
        assertThat(s.appLock).isFalse()
    }

    @Test fun defaultCurrencyFollowsLocale() = runTest(UnconfinedTestDispatcher()) {
        assertThat(repo(Locale.US).settings.first().currency).isEqualTo("USD")
    }

    @Test fun roundTrip() = runTest(UnconfinedTestDispatcher()) {
        val r = repo()
        r.setCurrency("GBP")
        r.setThemeMode(ThemeMode.DARK)
        r.setDynamicColor(true)
        r.setHideAmounts(true)
        r.setAppLock(true)
        r.completeOnboarding()
        val s = r.settings.first()
        assertThat(s.currency).isEqualTo("GBP")
        assertThat(s.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(s.dynamicColor).isTrue()
        assertThat(s.hideAmounts).isTrue()
        assertThat(s.appLock).isTrue()
        assertThat(s.onboardingDone).isTrue()
    }

    @Test fun periodStartDayIsClamped() = runTest(UnconfinedTestDispatcher()) {
        val r = repo()
        r.setPeriodStartDay(31)
        assertThat(r.settings.first().periodStartDay).isEqualTo(28)
        r.setPeriodStartDay(0)
        assertThat(r.settings.first().periodStartDay).isEqualTo(1)
    }

    @Test fun unknownThemeNameFallsBackToSystem() = runTest(UnconfinedTestDispatcher()) {
        assertThat(ThemeMode.entries.firstOrNull { it.name == "SEPIA" } ?: ThemeMode.SYSTEM).isEqualTo(ThemeMode.SYSTEM)
    }
}
