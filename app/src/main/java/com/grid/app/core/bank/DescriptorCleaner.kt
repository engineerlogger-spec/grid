package com.grid.app.core.bank

import com.grid.app.core.model.PaymentKind
import java.util.Locale

/**
 * Turns a card-statement descriptor into a merchant name a person would write:
 * "PAYPAL *NETFLIX" → Netflix (paid via PayPal), "LIDL 1234" → Lidl.
 */
object DescriptorCleaner {
    data class Cleaned(val merchant: String, val via: PaymentKind?)

    private val whitespace = Regex("\\s+")
    private val paypalMerchant = Regex("^(?:paypal|pp)\\s*\\*\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val paypalOnly = Regex("^paypal\\b.*", RegexOption.IGNORE_CASE)
    private val processor = Regex("^(?:(?:sq|sumup|iz|zettle|sp|ubr)\\s*\\*|zettle_\\*?)\\s*", RegexOption.IGNORE_CASE)
    private val storeNumber = Regex("\\s+#?\\d{3,}$")
    private val country = Regex(
        "\\s+(?:FR|FRA|GB|GBR|DE|DEU|ES|ESP|IT|ITA|NL|NLD|BE|BEL|LT|LTU|IE|IRL|PT|PRT|LU|LUX|US|USA)$",
    )

    fun clean(raw: String): Cleaned? {
        var text = raw.trim().replace(whitespace, " ")
        if (text.isEmpty()) return null

        var via: PaymentKind? = null
        paypalMerchant.matchEntire(text)?.let { match ->
            via = PaymentKind.PAYPAL
            text = match.groupValues[1].trim()
            if (text.isEmpty()) return Cleaned("PayPal", PaymentKind.PAYPAL)
        } ?: run {
            if (paypalOnly.matches(text)) return Cleaned("PayPal", PaymentKind.PAYPAL)
        }

        text = text.replace(processor, "")
        text = text.replace(storeNumber, "")
        if (country.containsMatchIn(text) && text.replace(country, "").isNotBlank()) text = text.replace(country, "")
        text = text.trim()
        if (text.isEmpty()) return null
        return Cleaned(if (text.none { it.isLowerCase() }) titleCase(text) else text, via)
    }

    /** Company-form suffixes keep their usual spelling: "ACME SAS" → "Acme SAS", not "Acme Sas". */
    private val legalForms = listOf("SA", "SAS", "SARL", "SASU", "EURL", "SNC", "AB", "AG", "BV", "NV", "SL", "SPA", "SRL", "LTD", "LLC", "INC", "PLC", "UAB", "AS", "OY")
        .associateBy { it } + ("GMBH" to "GmbH")

    private fun titleCase(text: String): String = text.split(' ').joinToString(" ") { word ->
        legalForms[word.uppercase(Locale.ROOT)] ?: word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}
