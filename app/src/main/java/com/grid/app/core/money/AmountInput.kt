package com.grid.app.core.money

import java.math.BigDecimal
import java.math.RoundingMode

sealed interface KeypadKey {
    data class Digit(val d: Int) : KeypadKey {
        init { require(d in 0..9) }
    }
    data object DoubleZero : KeypadKey
    data object Dot : KeypadKey
    data object Plus : KeypadKey
    data object Minus : KeypadKey
    data object Backspace : KeypadKey
    data object Clear : KeypadKey
}

/**
 * Immutable state of the quick-add keypad: a tiny calculator supporting `+` and `-`
 * (e.g. splitting a bill: `48+12.5-5`). Input is constrained as it's typed, so [expression]
 * is always well-formed apart from a possible trailing operator or dot.
 */
data class AmountInput(val expression: String = "", val fractionDigits: Int = 2) {

    fun press(key: KeypadKey): AmountInput = when (key) {
        is KeypadKey.Digit -> digit(key.d)
        KeypadKey.DoubleZero -> digit(0).digit(0)
        KeypadKey.Dot -> dot()
        KeypadKey.Plus -> operator('+')
        KeypadKey.Minus -> operator('-')
        KeypadKey.Backspace -> copy(expression = expression.dropLast(1))
        KeypadKey.Clear -> copy(expression = "")
    }

    /** The evaluated amount in minor units, or null when nothing has been typed. May be ≤ 0. */
    val valueMinor: Long? by lazy { evaluate() }

    val isExpression: Boolean get() = expression.any { it in OPERATORS }

    /** Human-friendly rendering: spaced operators and a true minus sign. */
    val display: String get() = expression.replace("+", " + ").replace("-", " − ")

    private val operand: String get() = expression.substringAfterLast('+').substringAfterLast('-')

    private fun digit(d: Int): AmountInput {
        val current = operand
        val next = when {
            current == "0" -> expression.dropLast(1) + d // collapse leading zero
            '.' in current && current.substringAfter('.').length >= fractionDigits -> return this
            '.' !in current && current.length >= MAX_INTEGER_DIGITS -> return this
            else -> expression + d
        }
        return copy(expression = next)
    }

    private fun dot(): AmountInput = when {
        fractionDigits == 0 -> this
        '.' in operand -> this
        operand.isEmpty() -> copy(expression = expression + "0.")
        else -> copy(expression = "$expression.")
    }

    private fun operator(op: Char): AmountInput {
        if (expression.isEmpty()) return this
        val trimmed = expression.trimEnd('.').let { if (it.last() in OPERATORS) it.dropLast(1) else it }
        return copy(expression = trimmed + op)
    }

    private fun evaluate(): Long? {
        if (expression.isEmpty()) return null
        var total = BigDecimal.ZERO
        var sign = 1
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty() && buffer.toString() != ".") {
                val value = BigDecimal(buffer.toString())
                total = if (sign > 0) total + value else total - value
            }
            buffer.clear()
        }
        for (c in expression) {
            when (c) {
                '+' -> { flush(); sign = 1 }
                '-' -> { flush(); sign = -1 }
                else -> buffer.append(c)
            }
        }
        flush()
        return total.movePointRight(fractionDigits).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    companion object {
        private const val MAX_INTEGER_DIGITS = 9
        private val OPERATORS = charArrayOf('+', '-')

        fun fromMinor(minor: Long, fractionDigits: Int): AmountInput = AmountInput(
            expression = BigDecimal.valueOf(kotlin.math.abs(minor), fractionDigits).stripTrailingZeros().toPlainString(),
            fractionDigits = fractionDigits,
        )
    }
}
