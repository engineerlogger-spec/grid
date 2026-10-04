package com.grid.app.core.insights

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BudgetAlertsTest {
    private val start = 20727L

    @Test fun eightyPercentAlert() {
        val alerts = BudgetAlerts.plan(start, listOf(LimitStatus("total", null, 100000, 81000)), emptySet())
        assertThat(alerts.single().threshold).isEqualTo(80)
        assertThat(alerts.single().keys).containsExactly("budget:$start:total:80")
    }

    @Test fun jumpingStraightTo100SendsOnlyOneAndMarksBoth() {
        val alerts = BudgetAlerts.plan(start, listOf(LimitStatus("cat:2", "Groceries", 20000, 25000)), emptySet())
        assertThat(alerts.single().threshold).isEqualTo(100)
        assertThat(alerts.single().keys).containsExactly("budget:$start:cat:2:80", "budget:$start:cat:2:100")
    }

    @Test fun alreadySentIsSkippedButHigherThresholdStillFires() {
        val sent = setOf("budget:$start:total:80")
        assertThat(BudgetAlerts.plan(start, listOf(LimitStatus("total", null, 100000, 90000)), sent)).isEmpty()
        assertThat(BudgetAlerts.plan(start, listOf(LimitStatus("total", null, 100000, 100000)), sent).single().threshold).isEqualTo(100)
    }

    @Test fun belowThresholdOrNoLimitIsSilent() {
        assertThat(BudgetAlerts.plan(start, listOf(LimitStatus("total", null, 100000, 79999), LimitStatus("cat:1", "X", 0, 500)), emptySet())).isEmpty()
    }
}
