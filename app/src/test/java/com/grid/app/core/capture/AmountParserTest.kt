package com.grid.app.core.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AmountParserTest {

    private fun first(text: String, default: String = "EUR") = AmountParser.find(text, default).firstOrNull()

    @Test fun symbolBeforeWithDot() {
        assertThat(first("€4.50")).isEqualTo(Money(450, "EUR"))
    }

    @Test fun symbolAfterWithComma() {
        assertThat(first("4,50 €")).isEqualTo(Money(450, "EUR"))
    }

    @Test fun groupingConventions() {
        assertThat(first("€1,234.56")).isEqualTo(Money(123456, "EUR"))
        assertThat(first("1.234,56 €")).isEqualTo(Money(123456, "EUR"))
        assertThat(first("1 234,56 €")).isEqualTo(Money(123456, "EUR"))
        assertThat(first("1 234,56 €")).isEqualTo(Money(123456, "EUR"))
        assertThat(first("CHF 1'234.50")).isEqualTo(Money(123450, "CHF"))
    }

    @Test fun isoCodesEitherSide() {
        assertThat(first("EUR 12.40")).isEqualTo(Money(1240, "EUR"))
        assertThat(first("12.40 EUR")).isEqualTo(Money(1240, "EUR"))
        assertThat(first("MAD 120,00")).isEqualTo(Money(12000, "MAD"))
        assertThat(first("EUR12.40")).isEqualTo(Money(1240, "EUR"))
    }

    @Test fun lettersGluedToNumbersAreNotCurrencies() {
        assertThat(AmountParser.find("Ref AB1234 confirmed", "EUR")).isEmpty()
        assertThat(AmountParser.find("Krone 1200", "SEK")).isEmpty()
    }

    @Test fun localSymbols() {
        assertThat(first("£3")).isEqualTo(Money(300, "GBP"))
        assertThat(first("120 DH")).isEqualTo(Money(12000, "MAD"))
        assertThat(first("¥1,200")).isEqualTo(Money(1200, "JPY"))
        assertThat(first("49,90 zł")).isEqualTo(Money(4990, "PLN"))
        assertThat(first("R$ 25,00")).isEqualTo(Money(2500, "BRL"))
    }

    @Test fun ambiguousDollarFollowsDefault() {
        assertThat(first("$5.00", default = "EUR")).isEqualTo(Money(500, "USD"))
        assertThat(first("$5.00", default = "CAD")).isEqualTo(Money(500, "CAD"))
        assertThat(first("C$5.00", default = "EUR")).isEqualTo(Money(500, "CAD"))
    }

    @Test fun symbolAndCodeForSameAmountCollapse() {
        val found = AmountParser.find("You paid €12.99 EUR to Spotify", "EUR")
        assertThat(found).containsExactly(Money(1299, "EUR"))
    }

    @Test fun numbersWithoutCurrencyAreIgnored() {
        assertThat(AmountParser.find("Paid €4.50 with Visa •••• 1234", "EUR")).containsExactly(Money(450, "EUR"))
        assertThat(AmountParser.find("Order 12345 is on its way", "EUR")).isEmpty()
    }

    @Test fun minusSignsAreIgnoredForMagnitude() {
        assertThat(first("−€20.00")).isEqualTo(Money(2000, "EUR"))
        assertThat(first("-20,00 €")).isEqualTo(Money(2000, "EUR"))
    }

    @Test fun threeDigitsAfterSeparatorIsGrouping() {
        assertThat(first("€1,200")).isEqualTo(Money(120000, "EUR"))
        assertThat(first("1.200 €")).isEqualTo(Money(120000, "EUR"))
    }
}
