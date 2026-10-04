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
    /** Payment terminals and platforms that prefix the real merchant ("Sunday*vapiano", "Nyx*caffenero", "Sc-boul…"). */
    private val processor = Regex("^(?:(?:sq|sumup|iz|zettle|sp|ubr|sunday|nyx|mol)\\s*\\*|zettle_\\*?|sc[-.])\\s*", RegexOption.IGNORE_CASE)
    /** "Anthropic* Claude Sub": the merchant before the star, the product after it. */
    private val starProduct = Regex("^(\\p{L}[\\p{L}0-9 .&'-]*?)\\s*\\*\\s*\\p{L}.*$") // not "Top-Up by *4421"
    /** Web-shop descriptors after "PAYPAL *": "bolt.eu/o/2609261" → bolt. */
    private val urlTail = Regex("/.*$")
    private val domain = Regex("\\.(?:com|eu|fr|io|net|org|co|uk|de|es|it)$", RegexOption.IGNORE_CASE)
    private val storeNumber = Regex("\\s+#?\\d{3,}$")
    private val gluedStoreNumber = Regex("(?<=\\p{L})\\d{5,}$")
    private val country = Regex(
        "\\s+(?:FR|FRA|GB|GBR|DE|DEU|ES|ESP|IT|ITA|NL|NLD|BE|BEL|LT|LTU|IE|IRL|PT|PRT|LU|LUX|US|USA)$",
    )

    fun clean(raw: String): Cleaned? {
        var text = raw.trim().replace(whitespace, " ")
        if (text.isEmpty()) return null

        var via: PaymentKind? = null
        paypalMerchant.matchEntire(text)?.let { match ->
            via = PaymentKind.PAYPAL
            // "openai *chatgpt S" → openai; "bolt.eu/o/2609261" → bolt
            text = match.groupValues[1].substringBefore('*').trim().replace(urlTail, "").replace(domain, "").trim()
            if (text.isEmpty()) return Cleaned("PayPal", PaymentKind.PAYPAL)
        } ?: run {
            if (paypalOnly.matches(text)) return Cleaned("PayPal", PaymentKind.PAYPAL)
        }

        text = text.replace(processor, "")
        starProduct.matchEntire(text)?.let { text = it.groupValues[1] }
        text = text.replace(storeNumber, "").replace(gluedStoreNumber, "")
        if (country.containsMatchIn(text) && text.replace(country, "").isNotBlank()) text = text.replace(country, "")
        text = text.trim()
        if (text.isEmpty()) return null
        val name = if (text.none { it.isLowerCase() } || text.none { it.isUpperCase() }) titleCase(text.uppercase(Locale.ROOT)) else text
        return Cleaned(name.replaceFirstChar { it.titlecase(Locale.ROOT) }, via)
    }

    /** Company-form suffixes keep their usual spelling: "ACME SAS" → "Acme SAS", not "Acme Sas". */
    private val legalForms = listOf("SA", "SAS", "SARL", "SASU", "EURL", "SNC", "AB", "AG", "BV", "NV", "SL", "SPA", "SRL", "LTD", "LLC", "INC", "PLC", "UAB", "AS", "OY")
        .associateBy { it } + ("GMBH" to "GmbH")

    /** Short words that are words, not acronyms: "LE COMPTOIR" → "Le Comptoir" while "EDF" stays "EDF". */
    private val shortWords = setOf("A", "AN", "AU", "AUX", "DE", "DU", "DES", "EL", "EN", "ET", "LA", "LE", "LES", "OF", "ST", "THE", "AND", "Y")

    private val vowels = Regex("[AEIOUY]")

    private fun titleCase(text: String): String {
        val words = text.split(' ')
        return words.joinToString(" ") { word ->
            val upper = word.uppercase(Locale.ROOT)
            // Short capitals are acronyms when they are the whole name (EDF) or unpronounceable (BNP),
            // not in people's names ("SAM TAYLOR" → Sam Taylor).
            val acronym = word.length <= 3 && upper !in shortWords && (words.size == 1 || !vowels.containsMatchIn(upper))
            when {
                upper in legalForms -> legalForms.getValue(upper)
                word.any { !it.isLetter() && it != '\'' && it != '-' && it != '.' } -> word // H&M
                acronym -> word
                else -> word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }
    }
}
