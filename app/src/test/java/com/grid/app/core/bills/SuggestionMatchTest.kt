package com.grid.app.core.bills

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.data.db.entities.SubscriptionEntity
import com.grid.app.core.model.CycleUnit
import com.grid.app.core.model.SubscriptionStatus
import org.junit.Test
import java.time.LocalDate

class SuggestionMatchTest {

    private fun d(s: String) = LocalDate.parse(s)

    private fun tracked(id: Long, name: String, amount: Long, next: String, varies: Boolean = false, payeeKey: String? = null, status: SubscriptionStatus = SubscriptionStatus.ACTIVE) =
        SubscriptionEntity(
            id = id, name = name, amountMinor = amount, currency = "EUR", cycleUnit = CycleUnit.MONTH, cycleCount = 1,
            anchorEpochDay = d(next).toEpochDay(), nextChargeEpochDay = d(next).toEpochDay(), categoryId = 1, autoLog = false,
            status = status, colorKey = "red", createdAt = 0, payeeKey = payeeKey, amountVaries = varies,
        )

    private fun found(payee: String, name: String, amount: Long, next: String, varies: Boolean = false) =
        FoundBillFacts(payee, name, amount, CycleUnit.MONTH, 1, d(next), varies)

    @Test fun theUsersOwnNamesAreRecognisedByScheduleAndAmount() {
        // "Loyer" on the 1st for €850 is the €850 transfer to J. Dupont on the 1st.
        assertThat(SuggestionMatch.isTracked(tracked(1, "Loyer", 85_000, "2026-11-01"), found("j dupont", "J. Dupont", 85_000, "2026-10-31"), emptySet())).isTrue()
        // A phone plan named by the user: a varying SFR bill around the same day and price.
        assertThat(SuggestionMatch.isTracked(tracked(2, "Forfait mobile", 1500, "2026-11-05"), found("sfr", "SFR", 1749, "2026-11-03", varies = true), emptySet())).isTrue()
    }

    @Test fun payeeNameOrPaymentsAlreadyLinkedMeanTracked() {
        assertThat(SuggestionMatch.isTracked(tracked(3, "Phone", 999, "2026-11-20", payeeKey = "sfr"), found("sfr", "SFR", 1499, "2026-11-05"), emptySet())).isTrue()
        assertThat(SuggestionMatch.isTracked(tracked(4, "Netflix", 1349, "2026-11-12"), found("paypal netflix", "Netflix", 1399, "2026-11-03"), emptySet())).isTrue()
        assertThat(SuggestionMatch.isTracked(tracked(5, "Box", 2999, "2026-11-20"), found("free", "Free", 3999, "2026-11-08"), setOf(5L))).isTrue()
    }

    @Test fun aDifferentBillIsNotMistakenForOne() {
        // Same day, very different amount: two separate bills.
        assertThat(SuggestionMatch.isTracked(tracked(6, "Gym", 3000, "2026-11-03"), found("netflix", "Netflix", 1349, "2026-11-03"), emptySet())).isFalse()
        // Same amount, different time of the month.
        assertThat(SuggestionMatch.isTracked(tracked(7, "Insurance", 3534, "2026-11-20"), found("deezer", "Deezer", 3534, "2026-11-06"), emptySet())).isFalse()
        // A suggestion never counts as "tracked".
        assertThat(SuggestionMatch.isTracked(tracked(8, "Netflix", 1349, "2026-11-03", status = SubscriptionStatus.SUGGESTED), found("netflix", "Netflix", 1349, "2026-11-03"), emptySet())).isFalse()
    }

    @Test fun charge30thAnd2ndAreTheSameDayAcrossMonthEnd() {
        assertThat(SuggestionMatch.isTracked(tracked(9, "Rent", 85_000, "2026-11-30"), found("landlord", "Landlord", 85_000, "2026-12-02"), emptySet())).isTrue()
    }
}
