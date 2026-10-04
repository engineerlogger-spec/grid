package com.grid.app.core.bills

import com.google.common.truth.Truth.assertThat
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.Cycle
import com.grid.app.core.model.CycleUnit
import com.grid.app.core.model.PendingDirection
import com.grid.app.core.model.PendingPayment
import com.grid.app.core.model.PendingStatus
import com.grid.app.core.model.Subscription
import com.grid.app.core.model.SubscriptionStatus
import org.junit.Test
import java.time.LocalDate

class BillsPlanningTest {

    private fun d(s: String) = LocalDate.parse(s)
    private val today = d("2026-10-04")
    private val subsCategory = Category(7, "Subscriptions", "subscriptions", "violet", CategoryKind.EXPENSE, 6)

    private fun sub(id: Long, name: String, anchor: String, next: String, remind: Int? = 1, status: SubscriptionStatus = SubscriptionStatus.ACTIVE, cycle: Cycle = Cycle.Monthly) =
        Subscription(id, name, 1399, "EUR", cycle, d(anchor), d(next), subsCategory, null, remind, true, status, "red", null)

    private fun pending(id: Long, title: String, due: String?, remind: Int? = 1, status: PendingStatus = PendingStatus.PENDING, direction: PendingDirection = PendingDirection.I_OWE) =
        PendingPayment(id, title, null, direction, 85000, "EUR", due?.let(::d), null, null, remind, status, null, null)

    @Test fun upcomingMergesSortsAndFlagsOverdue() {
        val items = UpcomingPlanner.upcoming(
            today, horizonDays = 7,
            subscriptions = listOf(sub(1, "Netflix", "2026-01-05", "2026-10-05"), sub(2, "Spotify", "2026-01-20", "2026-10-20")),
            pendings = listOf(pending(10, "Rent", "2026-10-07"), pending(11, "Doctor", "2026-10-01"), pending(12, "Loan", null)),
        )
        assertThat(items.map { it.title }).containsExactly("Doctor", "Netflix", "Rent").inOrder()
        assertThat(items.first().overdue).isTrue()
        assertThat(items[1].date).isEqualTo(d("2026-10-05"))
    }

    @Test fun weeklySubscriptionAppearsForEachChargeInHorizon() {
        val items = UpcomingPlanner.upcoming(today, 14, listOf(sub(1, "Gym", "2026-10-01", "2026-10-08", cycle = Cycle(CycleUnit.WEEK, 1))), emptyList())
        assertThat(items.map { it.date }).containsExactly(d("2026-10-08"), d("2026-10-15")).inOrder()
    }

    @Test fun inactiveAndSettledAreExcluded() {
        val items = UpcomingPlanner.upcoming(
            today, 30,
            listOf(sub(1, "Paused", "2026-01-05", "2026-10-05", status = SubscriptionStatus.PAUSED)),
            listOf(pending(10, "Paid", "2026-10-06", status = PendingStatus.DONE)),
        )
        assertThat(items).isEmpty()
    }

    @Test fun subscriptionReminderWindow() {
        val s = sub(1, "Netflix", "2026-01-05", "2026-10-05", remind = 1)
        assertThat(ReminderPlanner.plan(today, listOf(s), emptyList(), emptySet()).map { it.key }).containsExactly("sub:1:${d("2026-10-05").toEpochDay()}")
        assertThat(ReminderPlanner.plan(d("2026-10-03"), listOf(s), emptyList(), emptySet())).isEmpty()
        assertThat(ReminderPlanner.plan(today, listOf(s.copy(remindDaysBefore = null)), emptyList(), emptySet())).isEmpty()
    }

    @Test fun pendingDueAndOverdueReminders() {
        val due = pending(10, "Rent", "2026-10-07", remind = 3)
        val overdue = pending(11, "Doctor", "2026-10-01", remind = null)
        val plan = ReminderPlanner.plan(today, emptyList(), listOf(due, overdue), emptySet())
        assertThat(plan.map { it.key }).containsExactly("pend:10:${d("2026-10-07").toEpochDay()}", "pend:11:overdue")
        assertThat(plan.first { it.id == 10L }.daysUntil).isEqualTo(3)
        assertThat(plan.first { it.id == 11L }.kind).isEqualTo(ReminderKind.PENDING_OVERDUE)
    }

    @Test fun alreadySentRemindersAreSkipped() {
        val s = sub(1, "Netflix", "2026-01-05", "2026-10-05")
        val key = "sub:1:${d("2026-10-05").toEpochDay()}"
        assertThat(ReminderPlanner.plan(today, listOf(s), emptyList(), setOf(key))).isEmpty()
    }
}
