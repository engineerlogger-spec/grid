# Grid v2: M2 (Bills) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. The lead developer executes inline. Pure-domain tasks are test-first.

**Goal:** Subscriptions and pending payments, with automatic logging of renewals, reminders, and an "Upcoming" tile on Home (spec §3.6, §5.7).

**Architecture:**
- Pure scheduling math lives in `core/time/BillingSchedule.kt`, and upcoming/reminder planning in `core/bills/*`.
- Room DAOs and repositories for subscriptions and pending payments.
- `BillsWorker` (Hilt + WorkManager) books due charges and posts reminders through `core/notify/Notifier`.
- UI is a Bills tab with Subscriptions | Pending segments, plus full-screen editors.

## Tasks

### 2.1 BillingSchedule (TDD) → `core/time/BillingSchedule.kt`
- `chargeDate(anchor, cycle, n)`: computed from the anchor, so month-end anchors survive short months (Jan 31 → Feb 28 → Mar 31).
- `nextOnOrAfter(anchor, cycle, date)`, `nextAfter(anchor, cycle, date)`, `chargesThrough(anchor, cycle, from, through)`.
- `monthlyEquivalent(amountMinor, cycle)` and `yearly(amountMinor, cycle)`, rounded half-up.
- **Tests:**
  - month-end clamping
  - weekly
  - quarterly (3 months)
  - leap-day yearly (2028-02-29 → 2029-02-28 → 2032-02-29)
  - next on/after on the charge day itself
  - catch-up list across 3 missed months
  - monthly equivalents: weekly €10 → €43.33; yearly €120 → €10; quarterly €30 → €10

### 2.2 Upcoming + reminder planning (TDD) → `core/bills/Upcoming.kt`, `core/bills/Reminders.kt`
- **`UpcomingPlanner.upcoming(today, horizonDays, subscriptions, pendings)`:**
  - Subscriptions: active charges within the horizon.
  - Pendings: PENDING items due within the horizon, or overdue.
  - Sorted by date, with overdue items first.
- **`ReminderPlanner.plan(today, subscriptions, pendings, sentKeys)`:**
  - Subscription: remind when `today ∈ [charge − d, charge]`. Key `sub:{id}:{chargeEpoch}`.
  - Pending: remind when `today ∈ [due − d, due]` (key `pend:{id}:{dueEpoch}`), and once when overdue (key `pend:{id}:overdue`).
  - Skip anything whose key is in `sentKeys`.
- **Tests** cover each window edge, paused/cancelled exclusion, and `remindDaysBefore = null`.

### 2.3 Data → `core/data/db/dao/BillsDaos.kt`, `core/data/repo/{SubscriptionRepository,PendingRepository}.kt`, `core/model/Bills.kt`
- **`SubscriptionRepository`:**
  - observe, add, update, delete.
  - `setStatus`: resuming re-anchors `nextCharge` to on/after today, so a paused period is never back-charged.
  - `processDueCharges(today)`: books every charge ≤ today for active, auto-logging subscriptions. It's idempotent per (subscriptionId, day), then advances `nextCharge`.
- **`PendingRepository`:**
  - observe, add, update, delete.
  - `settle(id)`: creates an EXPENSE (I_OWE) or INCOME (OWED_TO_ME) transaction linked by `pendingId`.
  - `reopen(id)`: deletes the linked transaction.
- **Robolectric tests** for processDueCharges (catch-up, idempotency, pause/resume) and for settle/reopen.

### 2.4 Notifications + worker → `core/notify/{Channels,Notifier}.kt`, `core/work/{BillsWorker,WorkScheduler}.kt`, `GridApp` (`Configuration.Provider`, HiltWorkerFactory)
- Channels: `bills`, `budget`, `detected`, `backup`, `checkin`.
- `POST_NOTIFICATIONS` is requested from the Bills screen when the first reminder is set.
- The daily periodic work runs at about 09:00. A one-off catch-up run happens on app start.
- Sent-reminder keys are stored in DataStore (pruned after 60 days).

### 2.5 UI
- **Bills tab:**
  - Subscriptions: summary tile (monthly equivalent, yearly, count, next charge), then the list grouped as active, then paused/cancelled.
  - Pending: To pay / Owed to me, overdue highlighting, a Mark paid button, and a settled section.
- **Subscription editor:**
  - Popular-service presets, name, amount, cycle chips (weekly / monthly / every 3 months / yearly / custom).
  - Next charge date, category, method, reminder, auto-log, color.
  - Pause / resume / cancel / delete.
- **Pending editor:** direction, title, counterparty, amount, optional due date, category, reminder, note, delete.
- **Home "Upcoming" tile:** the next 7 days and the total due. Tapping it opens Bills.
- **E2E:** add Netflix monthly at €13.99 charging today, which auto-logs a transaction. Add "Rent" due in 3 days, which appears in Upcoming. Mark it paid, which creates an expense.
