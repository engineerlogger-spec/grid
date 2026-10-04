package com.grid.app.core.bills

import com.grid.app.core.model.PendingDirection
import com.grid.app.core.model.PendingPayment
import com.grid.app.core.model.PendingStatus
import com.grid.app.core.model.Subscription
import com.grid.app.core.model.SubscriptionStatus
import com.grid.app.core.time.BillingSchedule
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class UpcomingKind { SUBSCRIPTION, PENDING }

/** Something that will cost (or bring) money soon: a subscription charge or a pending payment. */
data class UpcomingItem(
    val kind: UpcomingKind,
    val id: Long,
    val title: String,
    val amountMinor: Long,
    val currency: String,
    val date: LocalDate,
    val overdue: Boolean,
    val direction: PendingDirection = PendingDirection.I_OWE,
    val colorKey: String,
    val iconKey: String?,
)

object UpcomingPlanner {

    /** Charges and dues within [horizonDays] of [today], overdue pendings first, then by date. */
    fun upcoming(
        today: LocalDate,
        horizonDays: Int,
        subscriptions: List<Subscription>,
        pendings: List<PendingPayment>,
    ): List<UpcomingItem> {
        val end = today.plusDays(horizonDays.toLong())
        val subs = subscriptions.filter { it.status == SubscriptionStatus.ACTIVE }.flatMap { s ->
            BillingSchedule.chargesThrough(s.anchor, s.cycle, maxOf(today, s.nextCharge), end).map { date ->
                UpcomingItem(UpcomingKind.SUBSCRIPTION, s.id, s.name, s.amountMinor, s.currency, date, overdue = false, colorKey = s.colorKey, iconKey = null)
            }
        }
        val dues = pendings.filter { it.status == PendingStatus.PENDING && it.due != null && !it.due.isAfter(end) }.map { p ->
            UpcomingItem(
                UpcomingKind.PENDING, p.id, p.title, p.amountMinor, p.currency, p.due!!, overdue = p.due.isBefore(today),
                direction = p.direction, colorKey = p.category?.colorKey ?: "amber", iconKey = p.category?.iconKey,
            )
        }
        return (subs + dues).sortedWith(compareByDescending<UpcomingItem> { it.overdue }.thenBy { it.date }.thenBy { it.title })
    }
}

enum class ReminderKind { SUBSCRIPTION_RENEWS, PENDING_DUE, PENDING_OVERDUE }

data class Reminder(
    /** Stable de-duplication key: a reminder with a key that was already sent is never sent again. */
    val key: String,
    val kind: ReminderKind,
    val id: Long,
    val title: String,
    val amountMinor: Long,
    val currency: String,
    val date: LocalDate,
    val daysUntil: Int,
    val direction: PendingDirection = PendingDirection.I_OWE,
)

object ReminderPlanner {

    fun plan(today: LocalDate, subscriptions: List<Subscription>, pendings: List<PendingPayment>, sentKeys: Set<String>): List<Reminder> {
        val subs = subscriptions.mapNotNull { s ->
            val days = s.remindDaysBefore ?: return@mapNotNull null
            if (s.status != SubscriptionStatus.ACTIVE) return@mapNotNull null
            val charge = BillingSchedule.nextOnOrAfter(s.anchor, s.cycle, maxOf(today, s.nextCharge))
            if (today.isBefore(charge.minusDays(days.toLong()))) return@mapNotNull null
            Reminder("sub:${s.id}:${charge.toEpochDay()}", ReminderKind.SUBSCRIPTION_RENEWS, s.id, s.name, s.amountMinor, s.currency, charge, daysBetween(today, charge))
        }
        val dues = pendings.mapNotNull { p ->
            val due = p.due ?: return@mapNotNull null
            if (p.status != PendingStatus.PENDING) return@mapNotNull null
            when {
                due.isBefore(today) ->
                    Reminder("pend:${p.id}:overdue", ReminderKind.PENDING_OVERDUE, p.id, p.title, p.amountMinor, p.currency, due, daysBetween(today, due), p.direction)
                p.remindDaysBefore != null && !today.isBefore(due.minusDays(p.remindDaysBefore.toLong())) ->
                    Reminder("pend:${p.id}:${due.toEpochDay()}", ReminderKind.PENDING_DUE, p.id, p.title, p.amountMinor, p.currency, due, daysBetween(today, due), p.direction)
                else -> null
            }
        }
        return (subs + dues).filter { it.key !in sentKeys }
    }

    private fun daysBetween(from: LocalDate, to: LocalDate) = ChronoUnit.DAYS.between(from, to).toInt()
}
