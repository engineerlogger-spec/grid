package com.grid.app.feature.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ParseMoneyTest {
    @Test fun decimalPointOrComma() {
        assertThat(parseMoney("12.50", "EUR")).isEqualTo(1250)
        assertThat(parseMoney("12,5", "EUR")).isEqualTo(1250)
        assertThat(parseMoney("1.5", "EUR")).isEqualTo(150)
    }

    @Test fun groupingSeparatorsInAnyConvention() {
        assertThat(parseMoney("1 234,56", "EUR")).isEqualTo(123456)
        assertThat(parseMoney("1,234.56", "USD")).isEqualTo(123456)
        assertThat(parseMoney("1.234,56", "EUR")).isEqualTo(123456)
        assertThat(parseMoney("1 234,56", "EUR")).isEqualTo(123456)
    }

    @Test fun threeTrailingDigitsMeanGrouping() {
        assertThat(parseMoney("1,234", "EUR")).isEqualTo(123400)
        assertThat(parseMoney("2.500", "EUR")).isEqualTo(250000)
    }

    @Test fun wholeNumbersAndZeroDecimalCurrencies() {
        assertThat(parseMoney("2500", "EUR")).isEqualTo(250000)
        assertThat(parseMoney("1200", "JPY")).isEqualTo(1200)
    }

    @Test fun emptyOrGarbageIsNull() {
        assertThat(parseMoney("", "EUR")).isNull()
        assertThat(parseMoney("  ", "EUR")).isNull()
        assertThat(parseMoney("abc", "EUR")).isNull()
    }

    @Test fun prefillText() {
        assertThat(moneyFieldText(250000, "EUR")).isEqualTo("2500")
        assertThat(moneyFieldText(1250, "EUR")).isEqualTo("12.5")
        assertThat(moneyFieldText(null, "EUR")).isEmpty()
    }
}
