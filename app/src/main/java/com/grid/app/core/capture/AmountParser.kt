package com.grid.app.core.capture

import com.grid.app.core.money.Currencies
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/** An amount found in text, in minor units. Always positive: direction comes from the wording. */
data class Money(val minor: Long, val currency: String)

/**
 * Finds money in free text ("Paid €4.50 at…", "1 234,56 €", "You sent $20.00 USD to…").
 * A number only counts when a currency symbol or ISO code sits right next to it, which keeps card
 * digits, order numbers and dates out.
 */
object AmountParser {

    // Grouped numbers first ("1,234.56", "1 234,56", "1'234.50"), then plain ones ("4.50", "120").
    private val number = Regex("""\d{1,3}(?:[   '.,]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,3})?""")

    private val symbols: List<Pair<String, String>> = listOf(
        "R$" to "BRL", "A$" to "AUD", "C$" to "CAD", "US$" to "USD", "Dhs" to "MAD", "DH" to "MAD", "Dh" to "MAD",
        "zł" to "PLN", "Kč" to "CZK", "kr" to "SEK", "€" to "EUR", "$" to "USD", "£" to "GBP", "¥" to "JPY",
        "₹" to "INR", "₺" to "TRY", "₽" to "RUB", "₩" to "KRW", "₪" to "ILS", "₦" to "NGN",
    )
    private val dollarCurrencies = setOf("USD", "CAD", "AUD", "NZD", "SGD", "HKD", "MXN", "TWD")
    private val kronaCurrencies = setOf("SEK", "NOK", "DKK", "ISK")
    private val isoCodes: Set<String> by lazy { Currency.getAvailableCurrencies().map { it.currencyCode }.toSet() }
    private val gap = setOf(' ', ' ', ' ')

    fun find(text: String, defaultCurrency: String): List<Money> {
        val result = mutableListOf<Money>()
        for (m in number.findAll(text)) {
            val currency = currencyBefore(text, m.range.first, defaultCurrency)
                ?: currencyAfter(text, m.range.last + 1, defaultCurrency)
                ?: continue
            val minor = toMinor(m.value, currency) ?: continue
            val money = Money(minor, currency)
            if (result.lastOrNull() != money) result += money
        }
        return result
    }

    private fun currencyBefore(text: String, numberStart: Int, default: String): String? {
        var end = numberStart
        while (end > 0 && (text[end - 1] in gap || text[end - 1] == '-' || text[end - 1] == '−')) end--
        val head = text.substring(0, end)
        symbols.firstOrNull { (sym, _) -> head.endsWith(sym) && boundaryBefore(head, head.length - sym.length) }?.let { (sym, code) ->
            return resolve(sym, code, default)
        }
        if (head.length >= 3) {
            val code = head.takeLast(3)
            if (code.all { it.isUpperCase() } && code in isoCodes && boundaryBefore(head, head.length - 3)) return code
        }
        return null
    }

    private fun currencyAfter(text: String, numberEnd: Int, default: String): String? {
        var start = numberEnd
        while (start < text.length && text[start] in gap) start++
        val tail = text.substring(start)
        symbols.firstOrNull { (sym, _) -> tail.startsWith(sym) && boundaryAfter(tail, sym.length) }?.let { (sym, code) ->
            return resolve(sym, code, default)
        }
        if (tail.length >= 3) {
            val code = tail.take(3)
            if (code.all { it.isUpperCase() } && code in isoCodes && boundaryAfter(tail, 3)) return code
        }
        return null
    }

    /** Letter symbols ("kr", "DH") must not be the tail of a longer word. */
    private fun boundaryBefore(s: String, index: Int): Boolean = index == 0 || !s[index - 1].isLetter() || !s[index].isLetter()
    private fun boundaryAfter(s: String, length: Int): Boolean = length >= s.length || !s[length].isLetter() || !s[length - 1].isLetter()

    private fun resolve(symbol: String, code: String, default: String): String = when (symbol) {
        "$" -> if (default in dollarCurrencies) default else "USD"
        "kr" -> if (default in kronaCurrencies) default else code
        "¥" -> if (default == "CNY") "CNY" else "JPY"
        else -> code
    }

    /** "1.234,56" / "1,234.56" / "4,50" / "1,200" → minor units for [currency]. */
    internal fun toMinor(raw: String, currency: String): Long? {
        val digitsOnly = raw.filterNot { it in gap || it == '\'' }
        val lastDot = digitsOnly.lastIndexOf('.')
        val lastComma = digitsOnly.lastIndexOf(',')
        val fraction = Currencies.fractionDigits(currency)
        val normalized = when {
            lastDot >= 0 && lastComma >= 0 -> {
                val decimalAt = maxOf(lastDot, lastComma)
                digitsOnly.substring(0, decimalAt).filter(Char::isDigit) + "." + digitsOnly.substring(decimalAt + 1)
            }
            lastDot >= 0 || lastComma >= 0 -> {
                val sep = if (lastDot >= 0) '.' else ','
                val count = digitsOnly.count { it == sep }
                val after = digitsOnly.length - digitsOnly.lastIndexOf(sep) - 1
                if (count > 1 || (after == 3 && fraction != 3)) digitsOnly.replace(sep.toString(), "")
                else digitsOnly.replace(sep, '.')
            }
            else -> digitsOnly
        }
        return runCatching {
            BigDecimal(normalized).movePointRight(fraction).setScale(0, RoundingMode.HALF_UP).longValueExact()
        }.getOrNull()?.takeIf { it > 0 }
    }
}
