package com.grid.app.core.bank

/** Card-network merchant category codes → seed category icon keys (see Seed), used when no learned rule exists. */
object MccCategories {
    private val exact: Map<Int, String> = buildMap {
        listOf(5411, 5422, 5441, 5451, 5462, 5499).forEach { put(it, "groceries") }
        listOf(5812, 5813, 5814).forEach { put(it, "restaurant") }
        listOf(4111, 4121, 4131, 4789, 5541, 5542, 7523).forEach { put(it, "transport") }
        listOf(5611, 5621, 5631, 5641, 5651, 5661, 5691, 5699).forEach { put(it, "clothing") }
        listOf(5311, 5331, 5399, 5732, 5734, 5942, 5945).forEach { put(it, "shopping") }
        listOf(4814, 4899, 4900).forEach { put(it, "bills") }
        listOf(5912, 8011, 8021, 8062, 8099).forEach { put(it, "health") }
        listOf(7832, 7841, 7922, 7991, 7996).forEach { put(it, "entertainment") }
        listOf(4511, 4722, 7011).forEach { put(it, "travel") }
        listOf(7230, 7297, 7298).forEach { put(it, "personal_care") }
        listOf(8211, 8220, 8299).forEach { put(it, "education") }
    }

    fun iconKeyFor(mcc: String?): String? {
        val code = mcc?.trim()?.toIntOrNull() ?: return null
        if (code in 3000..3999) return "travel" // airlines, car rental, hotels
        return exact[code]
    }
}
