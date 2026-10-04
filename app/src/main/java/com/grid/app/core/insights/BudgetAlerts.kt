package com.grid.app.core.insights

/** Spending against one limit: the overall goal (`total`) or a category (`cat:{id}`). */
data class LimitStatus(val scope: String, val name: String?, val limitMinor: Long, val spentMinor: Long)

/** An alert to post. [keys] are all the de-dup keys it settles (a 100% alert also retires the 80% one). */
data class BudgetAlert(val scope: String, val name: String?, val threshold: Int, val spentMinor: Long, val limitMinor: Long, val keys: List<String>)

object BudgetAlerts {
    private val thresholds = listOf(80, 100)

    fun plan(periodStartEpochDay: Long, statuses: List<LimitStatus>, sentKeys: Set<String>): List<BudgetAlert> = statuses.mapNotNull { s ->
        if (s.limitMinor <= 0) return@mapNotNull null
        val percent = s.spentMinor * 100 / s.limitMinor
        val reached = thresholds.lastOrNull { percent >= it } ?: return@mapNotNull null
        val key = { t: Int -> "budget:$periodStartEpochDay:${s.scope}:$t" }
        if (key(reached) in sentKeys) return@mapNotNull null
        BudgetAlert(s.scope, s.name, reached, s.spentMinor, s.limitMinor, thresholds.filter { it <= reached }.map(key))
    }
}
