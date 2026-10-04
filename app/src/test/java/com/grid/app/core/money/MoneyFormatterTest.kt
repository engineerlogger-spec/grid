package com.grid.app.core.money

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class MoneyFormatterTest {

    private val us = MoneyFormatter(Locale.US)

    /** ICU/JDK use (narrow) no-break spaces in some locales; compare against plain spaces. */
    private fun String.spaces() = replace(' ', ' ').replace(' ', ' ')

    @Test fun formatsEurosInUsLocale() {
        assertThat(us.format(1250, "EUR")).isEqualTo("€12.50")
    }

    @Test fun groupsThousands() {
        assertThat(us.format(123456, "USD")).isEqualTo("$1,234.56")
    }

    @Test fun usesLocaleConventionsInFrance() {
        assertThat(MoneyFormatter(Locale.FRANCE).format(123456, "EUR").spaces()).isEqualTo("1 234,56 €")
    }

    @Test fun signedPositiveGetsPlus() {
        assertThat(us.format(1250, "EUR", signed = true)).isEqualTo("+€12.50")
    }

    @Test fun signedNegativeGetsTrueMinus() {
        assertThat(us.format(-1250, "EUR", signed = true)).isEqualTo("−€12.50")
    }

    @Test fun unsignedNegativeAlsoUsesTrueMinus() {
        assertThat(us.format(-1250, "EUR")).isEqualTo("−€12.50")
    }

    @Test fun zeroDecimalCurrency() {
        assertThat(us.format(1200, "JPY")).isEqualTo("¥1,200")
    }

    @Test fun maskedHidesDigits() {
        assertThat(us.format(1250, "EUR", masked = true)).isEqualTo("€••••")
    }

    @Test fun partsSplitWholeAndFraction() {
        assertThat(us.parts(128450, "EUR")).isEqualTo(MoneyParts("€1,284", ".50"))
    }

    @Test fun partsForZeroDecimalCurrencyHaveNoFraction() {
        assertThat(us.parts(1200, "JPY")).isEqualTo(MoneyParts("¥1,200", ""))
    }

    @Test fun partsKeepTrailingSymbolWithFraction() {
        val parts = MoneyFormatter(Locale.FRANCE).parts(123456, "EUR")
        assertThat(parts.whole.spaces()).isEqualTo("1 234")
        assertThat(parts.fraction.spaces()).isEqualTo(",56 €")
    }

    @Test fun plainIsLocaleIndependentAndTrimmed() {
        assertThat(MoneyFormatter(Locale.FRANCE).plain(1250, "EUR")).isEqualTo("12.5")
        assertThat(us.plain(1200, "EUR")).isEqualTo("12")
        assertThat(us.plain(1205, "EUR")).isEqualTo("12.05")
    }

    @Test fun compactDropsZeroDecimals() {
        assertThat(us.compact(120000, "EUR")).isEqualTo("€1,200")
        assertThat(us.compact(120050, "EUR")).isEqualTo("€1,200.50")
    }

    @Test fun unknownCurrencyFallsBackToTwoDigits() {
        assertThat(Currencies.fractionDigits("XYZ")).isEqualTo(2)
        assertThat(Currencies.fractionDigits("JPY")).isEqualTo(0)
    }

    @Test fun defaultCurrencyFollowsLocale() {
        assertThat(Currencies.defaultFor(Locale.FRANCE)).isEqualTo("EUR")
        assertThat(Currencies.defaultFor(Locale.US)).isEqualTo("USD")
        assertThat(Currencies.defaultFor(Locale.ENGLISH)).isEqualTo("EUR") // no country → EUR fallback
    }

    @Test fun parsesMinorFromDecimalString() {
        assertThat(Currencies.toMinor("12.5", "EUR")).isEqualTo(1250)
        assertThat(Currencies.toMinor("1200", "JPY")).isEqualTo(1200)
    }
}
