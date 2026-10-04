# Grid v2: M3 (Insights, budgets, alerts) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Executed inline; pure-domain code is test-first.

**Goal:** The analysis requested in the brief ("spending analysis depending on type"): category breakdown, pace vs budget, month-over-month history, payment methods, top merchants, generated insight sentences, per-category budgets, and 80%/100% alerts (spec §3.7–3.8).

**Architecture:** `core/insights/InsightsEngine.kt` (pure) turns one query's worth of transactions into an `InsightsReport`. `core/insights/BudgetAlerts.kt` (pure) plans alerts. A `LedgerListener` implementation posts alerts after each change. All charts are custom Canvas in `core/designsystem/charts/`.

## Tasks

### 3.1 InsightsEngine (TDD)
`compute(period, today, goalMinor, entries: List<InsightEntry>, history: List<BudgetPeriod>, subscriptionsMonthlyMinor)` returns an `InsightsReport` containing:
- **KPIs:** spent, income, saved, daily average. The divisor is days elapsed, or the period length for past periods.
- **byCategory:** all expense categories, sorted, with fractions.
- **pace:** the cumulative spend per day up to today; a budget line `goal × day / length`; and the projection `spent / elapsed × length` (current period only).
- **history:** spent and income for the last 6 periods, oldest first.
- **byMethod**, and **topMerchants** (by title, top 5, with counts).
- **insights:** the biggest category increase vs the previous period (minimum €10 and +20%), projection vs goal, highest day, subscriptions' share of spending, and savings rate.
- **budgets:** per-category limit vs spent.

**Tests:**
- Daily average for current vs past periods.
- Projection.
- History bucketing across periods.
- Category-change threshold.
- Merchants grouping is case-insensitive.
- Budget progress.

### 3.2 BudgetAlerts (TDD)
`plan(periodStart, goal, spentTotal, categoryLimits: List<(categoryId, name, limit, spent)>, sentKeys)` returns alerts at 80% and 100% for the overall goal and each limited category.
- Key: `budget:{periodStartEpoch}:{total|cat:id}:{80|100}`.
- Only the highest threshold reached is alerted (crossing straight to 100% doesn't also send 80%).

### 3.3 Alert listener + wiring
`BudgetAlertListener : LedgerListener` (multibound). It computes the current period totals and posts on the `budget` channel. It uses `SentLog` for de-duplication.

### 3.4 Charts
- `PaceChart`: cumulative line with gradient fill, dashed budget line, and today marker.
- `BarPairsChart`: spent vs income per period, with month initials.
- `RatioBar`: a thin horizontal bar.
- All animate in on first show.

### 3.5 Insights screen
- Period switcher, then a KPI grid (2×2) and insight cards.
- Large donut with the total in the center, plus a legend. Tapping a legend row opens Activity filtered to that category.
- Pace tile with a projection line, then a 6-period bars tile.
- Budgets tile with a "Set budget" dialog (category + amount).
- Payment methods tile, then a top merchants tile.
- Empty state for a period with no data.

### 3.6 Verification
Unit tests pass. E2E: open Insights and check the KPIs match Activity; set a €20 Groceries budget, add €25 of groceries, and check the 100% alert notification posts.
