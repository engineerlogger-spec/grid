package com.grid.app.core.model

import java.text.Normalizer
import java.util.Locale

/** Normalizes merchant names so "STARBUCKS #1234" and "Starbucks" learn the same category. */
object MerchantKey {
    private val diacritics = Regex("\\p{Mn}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    fun of(merchant: String): String? {
        val folded = Normalizer.normalize(merchant, Normalizer.Form.NFD).replace(diacritics, "").lowercase(Locale.ROOT)
        val tokens = folded.split(separators).filter { it.isNotEmpty() && !it.all(Char::isDigit) }
        return tokens.joinToString(" ").takeIf { it.isNotEmpty() }
    }
}
