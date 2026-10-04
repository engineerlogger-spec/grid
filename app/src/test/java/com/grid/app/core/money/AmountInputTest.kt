package com.grid.app.core.money

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.money.KeypadKey.Backspace
import com.grid.app.core.money.KeypadKey.Clear
import com.grid.app.core.money.KeypadKey.Digit
import com.grid.app.core.money.KeypadKey.Dot
import com.grid.app.core.money.KeypadKey.DoubleZero
import com.grid.app.core.money.KeypadKey.Minus
import com.grid.app.core.money.KeypadKey.Plus
import org.junit.Test

class AmountInputTest {

    private fun type(vararg keys: Any, digits: Int = 2): AmountInput =
        keys.fold(AmountInput(fractionDigits = digits)) { acc, k ->
            acc.press(
                when (k) {
                    is Int -> Digit(k)
                    is KeypadKey -> k
                    else -> error("bad key $k")
                },
            )
        }

    @Test fun digitsAndDecimal() {
        val input = type(1, 2, Dot, 5)
        assertThat(input.expression).isEqualTo("12.5")
        assertThat(input.valueMinor).isEqualTo(1250)
    }

    @Test fun extraDecimalsAreIgnored() {
        assertThat(type(1, Dot, 2, 3, 4).expression).isEqualTo("1.23")
    }

    @Test fun secondDotInOperandIgnored() {
        assertThat(type(1, Dot, 2, Dot, 3).expression).isEqualTo("1.23")
    }

    @Test fun leadingDotBecomesZeroDot() {
        assertThat(type(Dot, 5).expression).isEqualTo("0.5")
    }

    @Test fun leadingZerosCollapse() {
        assertThat(type(0, 0, 5).expression).isEqualTo("5")
    }

    @Test fun doubleZeroAppendsTwoZeros() {
        assertThat(type(1, DoubleZero).expression).isEqualTo("100")
        assertThat(type(DoubleZero).expression).isEqualTo("0")
    }

    @Test fun addition() {
        val input = type(1, 2, Plus, 3, Dot, 2)
        assertThat(input.expression).isEqualTo("12+3.2")
        assertThat(input.valueMinor).isEqualTo(1520)
        assertThat(input.isExpression).isTrue()
    }

    @Test fun subtraction() {
        assertThat(type(2, 0, Minus, 4, Dot, 5).valueMinor).isEqualTo(1550)
    }

    @Test fun operatorReplacesTrailingOperator() {
        assertThat(type(5, Plus, Minus).expression).isEqualTo("5-")
    }

    @Test fun trailingOperatorIgnoredInValue() {
        assertThat(type(5, Minus).valueMinor).isEqualTo(500)
    }

    @Test fun operatorFirstIgnored() {
        assertThat(type(Plus, 5).expression).isEqualTo("5")
    }

    @Test fun negativeResultReportedAsIs() {
        assertThat(type(1, Minus, 3).valueMinor).isEqualTo(-200)
    }

    @Test fun integerPartCappedAtNineDigits() {
        assertThat(type(1, 2, 3, 4, 5, 6, 7, 8, 9, 1).expression).isEqualTo("123456789")
    }

    @Test fun backspaceAndClear() {
        assertThat(type(1, 2, Backspace).expression).isEqualTo("1")
        assertThat(type(1, 2, Plus, Backspace).expression).isEqualTo("12")
        assertThat(type(1, 2, Clear).expression).isEmpty()
        assertThat(type(1, 2, Clear).valueMinor).isNull()
    }

    @Test fun zeroFractionCurrencyDisablesDot() {
        val input = type(1, 2, Dot, 5, digits = 0)
        assertThat(input.expression).isEqualTo("125")
        assertThat(input.valueMinor).isEqualTo(125)
    }

    @Test fun emptyHasNoValue() {
        assertThat(AmountInput().valueMinor).isNull()
        assertThat(AmountInput().isExpression).isFalse()
    }

    @Test fun fromMinorPrefills() {
        assertThat(AmountInput.fromMinor(1250, 2).expression).isEqualTo("12.5")
        assertThat(AmountInput.fromMinor(1200, 2).expression).isEqualTo("12")
        assertThat(AmountInput.fromMinor(1250, 2).valueMinor).isEqualTo(1250)
    }

    @Test fun displayUsesTrueMinus() {
        assertThat(type(5, Minus, 2).display).isEqualTo("5 − 2")
        assertThat(type(5, Plus, 2).display).isEqualTo("5 + 2")
    }
}
