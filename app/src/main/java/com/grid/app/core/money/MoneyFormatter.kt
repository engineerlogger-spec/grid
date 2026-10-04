package com.grid.app.core.money

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.absoluteValue

/** A formatted amount split for display: big [whole] part (with symbol/sign) and dimmed [fraction]. */
data class MoneyParts(val whole: String, val fraction: String)

/**
 * Locale-aware money formatting from minor units. Negative amounts use a true minus sign (U+2212),
 * which reads better next to tabular figures than a hyphen.
 */
class MoneyFormatter(private val locale: Locale = Locale.getDefault()) {

    fun format(minor: Long, currency: String, signed: Boolean = false, masked: Boolean = false): String {
        if (masked) return masked(currency)
        return sign(minor, signed) + formatAbs(minor.absoluteValue, currency, Currencies.fractionDigits(currency))
    }

    /** Like [format] but drops ".00" for whole amounts (charts, compact tiles). */
    fun compact(minor: Long, currency: String, masked: Boolean = false): String {
        if (masked) return masked(currency)
        val digits = Currencies.fractionDigits(currency)
        val whole = digits == 0 || minor % pow10(digits) == 0L
        return sign(minor, false) + formatAbs(minor.absoluteValue, currency, if (whole) 0 else digits)
    }

    fun parts(minor: Long, currency: String, masked: Boolean = false, signed: Boolean = false): MoneyParts {
        if (masked) return MoneyParts(masked(currency), "")
        val digits = Currencies.fractionDigits(currency)
        val text = format(minor, currency, signed)
        if (digits == 0) return MoneyParts(text, "")
        val separator = symbols(currency).monetaryDecimalSeparator
        val at = text.lastIndexOf(separator)
        return if (at < 0) MoneyParts(text, "") else MoneyParts(text.substring(0, at), text.substring(at))
    }

    /** Locale-independent decimal string without trailing zeros, for prefilling the keypad. */
    fun plain(minor: Long, currency: String): String =
        Currencies.toDecimal(minor, currency).stripTrailingZeros().toPlainString()

    private fun formatAbs(abs: Long, currency: String, fractionDigits: Int): String {
        val value = Currencies.toDecimal(abs, currency).setScale(fractionDigits, java.math.RoundingMode.HALF_UP)
        val format = currencyFormat(currency) ?: return fallback(value.toPlainString(), currency)
        format.minimumFractionDigits = fractionDigits
        format.maximumFractionDigits = fractionDigits
        return format.format(value)
    }

    private fun currencyFormat(currency: String): DecimalFormat? = runCatching {
        (NumberFormat.getCurrencyInstance(locale) as DecimalFormat).apply {
            this.currency = Currency.getInstance(currency)
            isGroupingUsed = true
        }
    }.getOrNull()

    private fun symbols(currency: String): DecimalFormatSymbols =
        currencyFormat(currency)?.decimalFormatSymbols ?: DecimalFormatSymbols.getInstance(locale)

    private fun masked(currency: String): String {
        val sample = runCatching { formatAbs(100, currency, Currencies.fractionDigits(currency)) }.getOrDefault("1")
        return sample.replace(NumberRun, "••••")
    }

    private fun sign(minor: Long, signed: Boolean) = when {
        minor < 0 -> "−"
        signed && minor > 0 -> "+"
        else -> ""
    }

    private fun fallback(number: String, currency: String) = "$number $currency"

    private fun pow10(n: Int): Long { var r = 1L; repeat(n) { r *= 10 }; return r }

    private companion object {
        /** A run of digits with grouping/decimal punctuation and (no-break) spaces inside it. */
        val NumberRun = Regex("[0-9](?:[0-9.,'   ]*[0-9])?")
    }
}
