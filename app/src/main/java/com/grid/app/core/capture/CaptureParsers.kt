package com.grid.app.core.capture

import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.CaptureSource
import java.util.Locale

sealed interface CaptureParse {
    data class Parsed(val amount: Money, val merchant: String?, val direction: CaptureDirection) : CaptureParse
    /** Recognised but not a spend to record (declined, top-up, exchange, marketing…). */
    data class Ignored(val reason: String) : CaptureParse
    /** A payment given back ("Payment of €12 to Amazon reverted"): the entry recording it is reverted. */
    data class Reverted(val amount: Money, val merchant: String?) : CaptureParse
    /** Looks like a payment but the format wasn't understood — kept in the diagnostics log. */
    data object Unparsed : CaptureParse
}

/**
 * Heuristic parsers for payment notifications. Formats are undocumented and change between app
 * versions and languages, so this favours robust cues (an amount with a currency, at/to/from) over
 * exact templates, and never throws.
 */
object CaptureParsers {

    private val ignoreCues = Regex(
        """\b(declined|refused|failed|unsuccessful|insufficient|reverted|reversed|top.?up|topped up|exchanged|exchange|verification|verify|log ?in|sign.?in|offer|reward points|security)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val revertCues = Regex("""\b(reverted|reversed|reversal)\b""", RegexOption.IGNORE_CASE)
    /** Reversals that aren't of a payment out. */
    private val notPaymentCues = Regex("""\b(top.?up|topped up|exchanged?|refund(ed)?|received|transfer from)\b""", RegexOption.IGNORE_CASE)
    private val incomingCues = Regex(
        """\b(received|refund|refunded|returned|sent you|you got|you've got|cashback|money in|incoming|deposit(ed)?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val paymentCues = Regex("""\b(paid|payment|pay|spent|sent|purchase|charged|transaction|card)\b""", RegexOption.IGNORE_CASE)

    /** "… at Starbucks", "to John Smith", "from Sam", stopping at "with/using/on/via/for" or punctuation. */
    private val counterpartyAfter = Regex(
        """\b(?:at|to|from|chez|bei)\s+(.+?)(?=\s+(?:with|using|on|via|for|was|is|has)\b|[.,!;\n]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val sentYou = Regex("""^\s*(.+?)\s+sent you\b""", RegexOption.IGNORE_CASE)

    private val genericTitles = setOf(
        "google wallet", "wallet", "google pay", "gpay", "paypal", "revolut", "payment", "payment sent", "payment received",
        "money sent", "money received", "you've got money", "you've got money!", "purchase", "card payment", "transaction",
        "new transaction", "payment successful", "payment complete", "paid",
    )

    fun parse(source: CaptureSource, title: String, text: String, defaultCurrency: String): CaptureParse {
        val combined = listOf(title, text).filter { it.isNotBlank() }.joinToString("\n")
        if (combined.isBlank()) return CaptureParse.Ignored("empty")
        if (revertCues.containsMatchIn(combined) && !notPaymentCues.containsMatchIn(combined)) {
            val amount = AmountParser.find(text, defaultCurrency).firstOrNull() ?: AmountParser.find(title, defaultCurrency).firstOrNull()
            if (amount != null) return CaptureParse.Reverted(amount, merchant(source, title, text, defaultCurrency))
        }
        if (ignoreCues.containsMatchIn(combined)) return CaptureParse.Ignored("not a completed payment")

        val amount = AmountParser.find(text, defaultCurrency).firstOrNull()
            ?: AmountParser.find(title, defaultCurrency).firstOrNull()
            ?: return if (paymentCues.containsMatchIn(combined)) CaptureParse.Unparsed else CaptureParse.Ignored("no amount")

        val direction = if (incomingCues.containsMatchIn(combined)) CaptureDirection.IN else CaptureDirection.OUT
        val merchant = merchant(source, title, text, defaultCurrency)
        return CaptureParse.Parsed(amount, merchant, direction)
    }

    /** "returned to Visa •••• 1234", "paid from your card" — the payment instrument, not the counterparty. */
    private val instrument = Regex(
        """^(visa|master ?card|amex|american express|maestro|discover|card|debit card|credit card|your card|your account|account|bank|balance|apple pay|google pay)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun counterparty(source: String): String? =
        counterpartyAfter.findAll(source).mapNotNull { cleanMerchant(it.groupValues[1]) }.firstOrNull { !instrument.containsMatchIn(it) }

    private fun merchant(source: CaptureSource, title: String, text: String, defaultCurrency: String): String? {
        sentYou.find(text)?.let { return cleanMerchant(it.groupValues[1]) }
        // An explicit "at/to/from X" is the most reliable cue for every app.
        counterparty(text)?.let { return it }
        counterparty(title)?.let { return it }
        // Wallet and Revolut put the merchant in the title for card payments.
        val titleIsMerchant = source != CaptureSource.PAYPAL &&
            title.isNotBlank() &&
            title.trim().lowercase(Locale.ROOT).trimEnd('!', '.') !in genericTitles &&
            AmountParser.find(title, defaultCurrency).isEmpty()
        return if (titleIsMerchant) cleanMerchant(title) else null
    }

    /** Strips emoji, masked card numbers and stray punctuation; null when nothing meaningful is left. */
    fun cleanMerchant(raw: String): String? {
        val withoutCard = raw.replace(Regex("""[•·*]{2,}\s*\d{2,4}"""), " ")
        // Emoji live in surrogate pairs or the "other symbol" category; FE0F / 200D are their joiners.
        val withoutEmoji = withoutCard.filter { ch ->
            !(Character.isSurrogate(ch) || Character.getType(ch) == Character.OTHER_SYMBOL.toInt() || ch == '️' || ch == '‍')
        }
        val cleaned = withoutEmoji.replace(Regex("""\s+"""), " ").trim().trimEnd('.', ',', '!', ':', ';', '-').trim()
        return cleaned.takeIf { it.isNotEmpty() && it.any(Char::isLetter) }?.take(48)
    }
}
