package com.grid.app.core.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.Locale

/** ISO-4217 helpers. Amounts are always stored as minor units (cents) + currency code. */
object Currencies {

    fun fractionDigits(code: String): Int =
        runCatching { Currency.getInstance(code).defaultFractionDigits }.getOrNull()?.takeIf { it >= 0 } ?: 2

    /** The currency of [locale]'s country, or EUR when the locale has no country. */
    fun defaultFor(locale: Locale): String =
        runCatching { Currency.getInstance(locale).currencyCode }.getOrNull() ?: "EUR"

    fun symbol(code: String, locale: Locale = Locale.getDefault()): String =
        runCatching { Currency.getInstance(code).getSymbol(locale) }.getOrDefault(code)

    fun displayName(code: String, locale: Locale = Locale.getDefault()): String =
        runCatching { Currency.getInstance(code).getDisplayName(locale) }.getOrDefault(code)

    /** "12.5" → 1250 for EUR. Rounds half-up to the currency's precision. */
    fun toMinor(decimal: String, code: String): Long =
        BigDecimal(decimal).movePointRight(fractionDigits(code)).setScale(0, RoundingMode.HALF_UP).longValueExact()

    fun toDecimal(minor: Long, code: String): BigDecimal = BigDecimal.valueOf(minor, fractionDigits(code))

    /** All ISO currencies, sorted by code. */
    fun all(): List<String> = Currency.getAvailableCurrencies().map { it.currencyCode }.sorted()
}
