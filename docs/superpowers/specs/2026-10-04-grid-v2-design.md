# Grid v2: design spec

**Date:** 2026-10-04
**Author:** Claude (lead developer)
**Status:** Approved under delegation, and implemented in milestones M0–M6 (branch `v2`, merged to `main`). The owner handed full control to the lead developer ("recreate the whole project the way you see it") and went offline, so the decisions below were made autonomously. Open questions for the owner are listed in §13; none of them block the build.

**Changes made during implementation:**
- `compileSdk 37`, because current AndroidX requires it.
- Projections count fixed bills (subscriptions, settled pending payments) once, instead of extrapolating them.
- A check-in never books the same income twice in one period.
- The goal carries over when the pay day moves.

## 1. Why a rebuild

Jules's draft (PR #1) builds and runs, but it's a scaffold:
- 3 bare screens with a default purple theme and no icon.
- Payment capture can never fire because of over-escaped strings and regex.
- A mocked Drive backup.
- Money stored as `Double`.
- A destructive DB migration policy.
- 2023 dependencies and `targetSdk 34`, which Play has refused since 2026-08-31.
- No tests.

The owner judged it "very basic, not what I expected". Grid v2 is a rewrite, not a refactor. The only thing kept is the name.

**Owner's brief (baseline):** manage monthly spendings, subscriptions and pending payments. It should have:
- a pretty UI with dark/light/system theme
- Google Drive backup & sync "the way WhatsApp does"
- monthly spending goals
- a prompt for salary income each month
- spending analysis by type
- a rich feature set, with spendings that are easy and fast to add
- payment sync from Google Wallet, PayPal and Revolut

And the bar: "impress me".

## 2. Product principles

1. **Two actions to log a spend:** key in the amount, tap the category, and it's saved. Everything else is optional.
2. **Answer "how am I doing this month?" in one glance.** Home shows what's left to spend, the daily allowance that keeps you on track, and what's due soon.
3. **Capture, don't type.** Payments from Wallet/PayPal/Revolut notifications become one-tap confirmations, and the app learns merchant→category.
4. **Everything stays on the device unless you back it up.** No server and no analytics. The only network calls go to Google Drive, and only when backup is enabled.
5. **Distinctive, not generic.** The visual identity is built on the name: a grid.

## 3. Feature scope (v1)

### 3.1 Onboarding (first run)
Pages:
1. Welcome, with **"Restore from Google Drive"** as a secondary action (the WhatsApp pattern).
2. Currency, defaulting to the device locale's currency, with a searchable picker.
3. This month's income: one or more income sources (e.g. Salary 2,500).
4. Monthly spending goal, with suggestion chips (70% / 80% / 90% of income) or a custom amount.
5. Theme (System / Light / Dark).
6. "Make it automatic": optional payment detection and Drive backup cards, each skippable.

### 3.2 Monthly income check-in (the "ask for salary each month" requirement)
- **When it appears:** when a new budget period starts and its income hasn't been confirmed. It shows a full-screen check-in on next app open, plus a notification on the period's first morning.
- **Prefilled content:** income sources come from saved templates with last period's amounts. Each source can be edited, toggled off or added to, and the spending goal is prefilled from last period.
- **On confirm:** it creates INCOME transactions (source = CHECKIN) and stores the period's goal.
- **"Later":** dismisses it until the next app open. Home shows a banner until it's done.

### 3.3 Quick add
- A modal bottom sheet reachable from:
  - the nav-bar `+`
  - the home-screen widget
  - the Quick Settings tile
  - the launcher shortcuts ("Add expense", "Add income")
  - capture notifications
- Expense / Income segmented toggle.
- Custom keypad with calculator support (`12.5+3.2`), with the live result shown.
- Category grid: the 7 most-used categories (last 90 days, falling back to the default order) plus "More". **Tapping a category saves immediately** if the amount is > 0. Long-press selects it without saving, so details can be added.
- Optional chips: date (Today / Yesterday / pick), payment method (defaults to last used), note/merchant.
- Suggestion row: the 4 most frequent (merchant, amount, category) combos from the last 60 days. One tap saves.
- After saving, the sheet closes and a snackbar shows "Saved €12.50 · Restaurants" with an Undo action.
- The same sheet edits existing transactions (with a Save button, and Delete with undo).

### 3.4 Home (bento dashboard)
- **Header:** period name ("October"), "Day 14 of 31 · 17 days left", a hide-amounts eye toggle, and a settings avatar.
- **Hero tile:** *Left to spend* (goal − spent), *€X/day keeps you on track*, and the **month grid**. The month grid has one cell per day of the period: past days are colored by spend vs daily allowance (lime shades under, coral over), today is outlined, and future days are empty. Tapping a cell opens that day in Activity.
- **Spent tile:** spent vs goal with a progress bar.
- **Income tile:** confirmed income, with a check-in prompt if it's missing.
- **Upcoming tile:** the next bills due within 7 days (subscriptions + pending), with the total due.
- **Categories tile:** a mini donut with the top 3 categories.
- **Detected tile:** shown only when captured payments await review.
- **Recent:** the last 5 transactions.

### 3.5 Activity
- Month switcher with totals (spent / income).
- Search across merchant, note and category.
- Filter chips: type, category (multi-select), payment method.
- List grouped by day with day totals. Tap to edit, swipe to delete (with undo).
- Opens pre-filtered when navigated from Home (day cell) or Insights (category slice).

### 3.6 Bills
**Subscriptions:**
- Summary card: monthly equivalent, yearly total, active count.
- List sorted by next charge, with a countdown ("in 3 days") and a brand-colored monogram tile.
- Add/edit screen. Presets for ~30 popular services (name, color, default category, typical cycle); the user enters the amount. Cycle is every N weeks / months / years.
- Each subscription has a reminder (N days before, default 1), auto-log on billing date (default on), and a status (active / paused / cancelled).
- Billing dates anchor to the original day-of-month and clamp in short months (31 Jan → 28 Feb → 31 Mar).

**Pending payments:**
- Two groups: *To pay* and *Owed to me*. Each item has a title, payee, amount, optional due date, category, note and reminder.
- Overdue items are highlighted.
- **Mark paid / Mark received** creates the matching EXPENSE / INCOME transaction (source = PENDING) and links it.

### 3.7 Insights
- Period switcher.
- KPI row: spent, income, saved (income − spent), daily average.
- **Donut by category**, with legend and % values. Tapping a slice opens Activity filtered to that category.
- **Pace chart:** cumulative spend line vs the straight budget-pace line, plus a projected end-of-period spend.
- **6-period bars:** spent vs income.
- **Budgets:** per-category limits with progress, and "Set budget" inline.
- **By payment method** (bars) and **top merchants**.
- **Insight cards:** generated sentences such as biggest category change vs last period, highest day, projection vs goal, and subscriptions' share of spend.

### 3.8 Budgets & alerts
- A per-period overall goal (from check-in), plus optional per-category monthly limits.
- Notifications at 80% and 100% of the overall goal and of each category limit. Each fires at most once per period per threshold.

### 3.9 Payment detection (Google Wallet, PayPal, Revolut)
- **Mechanism:** a `NotificationListenerService` limited to `com.google.android.apps.walletnfcrel`, `com.paypal.android.p2pmobile` and `com.revolut.revolut`. None of the three offers a personal-account API, so this is the only viable path. Everything stays on-device.
- **Parsing:** heuristic parsers per source share a locale-robust amount parser. It handles `€12.40`, `12,40 €`, `EUR 12.40`, `$1,234.56`, `1.234,56 €`, `£3`, `MAD 120,00` and similar. The parsers also extract the merchant ("at X", "to X", or the title) and the direction (refund/received → income).
- **Dedupe:** by notification key + content hash within 10 minutes.
- **Results** land in a **Detected inbox** (Home tile and a settings entry).
  - **Known merchant with "Auto-add" on:** the transaction is created silently, with an "Added · Undo" notification.
  - **Otherwise:** a notification "€4.50 at Starbucks · Google Wallet" offers actions for the 2 predicted categories plus "Review". Review opens quick add prefilled.
- **Learning:** confirming a capture upserts a `merchant → category, method` rule.
- **Diagnostics log** (on by default, local only, 30-day retention, clearable): raw title/text of notifications from those 3 apps that failed to parse. This lets the parsers be tuned against the owner's real notifications.
- **Enable flow:** a guided screen to open notification access settings. It detects the sideload "restricted settings" block (Android 13+) and explains *App info → ⋮ → Allow restricted settings*. Installing via `adb`/Play avoids the block.

### 3.10 Google Drive backup (the WhatsApp model)
- **Storage:** Drive **appDataFolder**, which is hidden from the user's Drive UI, exactly like WhatsApp. The scope is `drive.appdata`, which Google classifies as **non-sensitive**, so no Google verification is needed.
- **Settings screen:** Google account, last backup (date, size), **Back up now**, auto-backup frequency (Off / Daily / Weekly / Monthly; default Weekly), "Back up over Wi-Fi only" (default on).
- **Backup file:** `grid-backup.zip` containing:
  - `manifest.json`: format version, schema version, app version, created-at, device model, row counts
  - `grid.db`: a consistent snapshot via `VACUUM INTO`
  - `settings.json`
- The latest 2 backups are kept.
- **Restore:** from onboarding, or from Settings (with confirmation). The manifest is validated, the DB file replaced, and the app process restarted. Older schemas migrate on open via Room migrations.
- **Auto-backup:** a WorkManager periodic job (network constraint per the Wi-Fi setting, battery not low). It authorizes silently. If consent is needed again, it posts a "Tap to reconnect Google Drive" notification.
- **Auth:** Google Identity `AuthorizationClient`, with no deprecated GoogleSignIn. REST calls go through OkHttp, with no google-api-client bloat.
- **Unconfigured builds:** this needs a Google Cloud OAuth *Android* client for `com.grid.app` plus the signing SHA-1 (owner setup, documented in `docs/GOOGLE_DRIVE_SETUP.md`). Until that's done, the screen explains the situation and offers local backup.
- **Local backup / restore:** the same zip via the Storage Access Framework, plus **CSV export** of transactions.
- **"Sync" in this spec means WhatsApp semantics:** automatic periodic backup plus restore on a new device. It is not live multi-device sync, which is out of scope.

### 3.11 Settings
- **Appearance:** theme, Material You dynamic color (off by default, so Grid keeps its identity).
- **Money:** currency, budget period start day (1–28, for people paid on the 25th), categories (add/edit/reorder/archive, icon + color), payment methods, income sources.
- **Notifications:** bill reminder time, budget alerts, check-in reminder.
- **Payment detection:** status/permission, per-source toggles, auto-add for known merchants, Detected inbox, diagnostics log.
- **Backup:** Drive, local backup/restore, CSV export.
- **Security:** app lock (biometric / device credential, re-locks after 1 min in background), hide amounts by default.
- **About.**

### 3.12 System integrations
- **Glance home-screen widget**, resizable. It shows left to spend, a progress bar, days left and a `+` button to quick add.
- **Quick Settings tile** "Add expense".
- **Static launcher shortcuts:** Add expense, Add income, Bills.
- **Notification channels:** Bills & reminders, Budget alerts, Detected payments, Backup, Check-in.
- **Behaviour:** splash screen, edge-to-edge, predictive back, adaptive + themed (monochrome) launcher icon.

### Out of scope for v1
- Live multi-device sync.
- Bank/open-banking APIs.
- Multi-currency conversion (each transaction stores its currency code. Captures in a foreign currency go to the inbox for the user to enter the converted amount).
- Receipts/photos.
- Shared/family budgets.
- Encrypted backups.
- Localization beyond English (all strings are in resources, ready for FR/AR).
- Project checklists (mentioned in the old repo description but not in the brief).

## 4. Visual design system: "Graphite & Lime"

The mockup is in `.superpowers/brainstorm/*/content/visual-direction.html` (local).

**Palette:**
- **Dark:** background `#0B0D0E`, tile `#15191B`, raised `#1B2023`, hairline `#232A2E`, text `#F2F4F3`, muted `#8E979B`.
- **Light:** background `#F3F2EC` (warm paper), tile `#FFFFFF`, raised `#F8F7F2`, hairline `#E3E1D8`, text `#111315`, muted `#62696D`.
- **Light-mode hero tile** stays graphite as the visual anchor.
- **Accents:**
  - **Lime** `#C8F560`: primary. On light backgrounds, lime is used as a fill with ink text. Lime text on light becomes `#4E7D00`.
  - **Coral** `#FF6B5A`: over budget / errors. Light: `#D93D2B`.
  - **Amber** `#FFB547`: due soon. Light: `#B26A00`.
  - **Sky** `#6EC1FF`: income. Light: `#2E7FD0`.
- **Category hues:** 16 colors tuned for both themes, each with a tinted container.

**Type:**
- **Space Grotesk** (display, headline, all money figures, tabular numerals via `tnum`).
- **Inter** (body, labels).
- Both are bundled TTF, SIL OFL.

**Shapes:**
- Tiles: 22dp radius with a 1dp hairline border and no elevation shadows.
- Sheets: 30dp radius.
- Chips: pill.
- Category icon tiles: 10–12dp radius.

**Background:** a faint 24dp grid pattern on screen backgrounds (alpha 2.5–3.5%).

**Motion:**
- Spring-based number transitions for money (`animateFloatAsState`).
- Month grid cells fade in staggered.
- Shared-element transitions are not required.

**Signature components:** `MonthGrid`, `MoneyText` (big integer + dimmed decimals), `Tile`, `CategoryBadge`, `Keypad`, and the charts (`DonutChart`, `PaceChart`, `BarPairsChart`, `HBarChart`). All charts are custom Compose Canvas, with no chart library, for full visual control.

**Accessibility:** contrast ≥ 4.5:1 for text, content descriptions on icons, charts paired with text legends, and amounts readable by TalkBack ("12 euros 50").

## 5. Architecture

### 5.1 Stack (latest stable as of 2026-10-04)

| Concern | Choice |
|---|---|
| Build | Gradle 9.8.0, AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.20, KSP 2.3.12, version catalog |
| Targets | `minSdk 26`, `targetSdk 36`, `compileSdk 37` (current AndroidX/OkHttp require it), Java 17 bytecode |
| UI | Compose BOM 2026.09.00, Material 3, Navigation Compose 2.10.2 (type-safe routes), core-splashscreen 1.2.0 |
| DI | Hilt 2.60.1 + hilt-navigation-compose / hilt-work 1.4.0 |
| Persistence | Room 2.8.5 (exported schemas, explicit migrations), DataStore Preferences 1.2.1 |
| Background | WorkManager 2.12.0 |
| Widget | Glance 1.2.0 |
| Auth / Drive | play-services-auth 22.0.0 (AuthorizationClient), OkHttp 5.5.0, kotlinx-serialization 1.11.0 |
| Security | androidx.biometric 1.1.0 |
| Icons | material-icons-extended 1.7.8 (category icons) |
| Tests | JUnit 4, Truth, Turbine, kotlinx-coroutines-test, Robolectric 4.17 (Room/DAO + Android-dependent units), room-testing, work-testing |

### 5.2 Structure
A single `:app` module (`com.grid.app`). Package boundaries are strict, and pure-Kotlin domain code has no Android imports, so it unit-tests fast on the JVM.

```
com.grid.app
├── GridApp, MainActivity                (Hilt app, WorkManager config, lock gate, deep links)
├── core
│   ├── model        (pure) Money, Currency, TxType, Cycle, Period, PaymentKind …
│   ├── money        (pure) MoneyFormat, AmountExpression (keypad calculator)
│   ├── time         (pure) BudgetPeriods, BillingSchedule, Clock (injectable)
│   ├── insights     (pure) InsightsEngine: pace, projection, deltas, sentences
│   ├── designsystem theme/, components/, charts/
│   ├── data         db/ (entities, DAOs, migrations), prefs/ (DataStore), repo/ (repositories)
│   ├── notify       channels + Notifier
│   └── work         BillsWorker, CheckInWorker, BackupWorker, scheduler
├── feature
│   ├── onboarding, checkin, home, add, activity, bills, insights, settings
│   ├── capture      service, parsers (pure), inbox UI, enable flow
│   ├── backup       drive client, archive (zip/manifest), local SAF, UI
│   ├── widget, tile, lock
└── navigation       GridNavHost, routes, deep links
```

### 5.3 Layering and data flow
- **UI layer:** Composable screens → `@HiltViewModel` → expose an immutable `UiState` via `StateFlow`. These are built with `combine(...).stateIn(viewModelScope, WhileSubscribed(5s), initial)`, with explicit `Loading` states (no "null means loading").
- **Repositories** wrap DAOs and DataStore, expose `Flow`s, and own invariants. For example, saving a transaction also updates the merchant rule and triggers budget-alert evaluation and widget refresh.
- **Time:** all "now" goes through an injectable `Clock` / `ZoneId` so period math is testable.

### 5.4 Data model (Room, schema v1)
Money is always `amountMinor: Long` plus a `currency: String` (ISO 4217). Dates use `epochDay: Long` (LocalDate) for date-only values and `epochMillis` for instants.

- **`categories`**: id, name, iconKey, colorKey, kind (EXPENSE/INCOME), position, archived, monthlyLimitMinor?
- **`payment_methods`**: id, name, kind (CASH/CARD/GOOGLE_WALLET/PAYPAL/REVOLUT/BANK/OTHER), position, archived
- **`transactions`**:
  - id, type (EXPENSE/INCOME), amountMinor, currency, categoryId, paymentMethodId?, merchant?, note?
  - occurredAt (millis), createdAt, updatedAt
  - source (MANUAL/CAPTURE/SUBSCRIPTION/PENDING/CHECKIN)
  - subscriptionId?, pendingId?, captureId?
  - indexes on occurredAt, categoryId
- **`subscriptions`**:
  - id, name, amountMinor, currency, cycleUnit (WEEK/MONTH/YEAR), cycleCount
  - anchorEpochDay, nextChargeEpochDay
  - categoryId, paymentMethodId?, remindDaysBefore?, autoLog, status, colorKey, note?, createdAt
- **`pending_payments`**: id, title, counterparty?, direction (I_OWE/OWED_TO_ME), amountMinor, currency, dueEpochDay?, categoryId?, note?, remindDaysBefore?, status (PENDING/DONE/CANCELLED), settledAt?, transactionId?, createdAt
- **`period_plans`**: periodStartEpochDay (PK), goalMinor, incomeConfirmedAt?
- **`income_sources`**: id, name, amountMinor, categoryId, position, active
- **`captures`**: id, source (WALLET/PAYPAL/REVOLUT), postedAt, title, text, amountMinor?, currency?, merchant?, direction, status (NEW/ADDED/DISMISSED/UNPARSED), transactionId?, dedupeKey (unique)
- **`merchant_rules`**: merchantKey (PK, normalized), categoryId, paymentMethodId?, hits, updatedAt

Seeds on first create:
- **16 expense categories:** Restaurants, Groceries, Transport, Shopping, Clothing, Bills & Utilities, Subscriptions, Housing, Health, Entertainment, Travel, Education, Gifts, Personal care, Services, Other.
- **4 income categories:** Salary, Freelance, Refunds, Other income.
- **Payment methods:** Cash, Card, Google Wallet, PayPal, Revolut.

Schemas are exported to `app/schemas/`, and every future change ships a tested `Migration`. **Destructive fallback is never used.**

### 5.5 Settings (DataStore)
- **General:** currency, themeMode, dynamicColor, periodStartDay, onboardingDone.
- **Security:** appLock, hideAmounts.
- **Notifications:** billReminderHour, budgetAlerts, checkInReminder.
- **Capture:** capture.enabled per source, capture.autoAdd, capture.diagnostics.
- **Backup:** backup.frequency, backup.wifiOnly, backup.lastAt, backup.lastSize, backup.accountHint.
- **Alert bookkeeping:** firedAlerts (set of "periodStart:scope:threshold").

### 5.6 Budget periods
`BudgetPeriods.periodFor(date, startDay)`: if `date.day ≥ startDay`, the period runs from `date.withDay(startDay)` to the same day next month (exclusive). Otherwise it starts one month earlier. `startDay` is clamped to 1–28. The period name uses the month containing most of its days.

### 5.7 Background work
- **`BillsWorker`:** daily at the reminder hour, and also enqueued on app start as a catch-up.
  1. For each active subscription with `nextCharge ≤ today`, auto-log the expense(s) dated on the charge day(s), then advance `nextCharge` (this catches up multiple missed cycles).
  2. Post reminders for subscriptions and pending items due within their reminder window, plus overdue pending items. Duplicate reminders are prevented with per-item/day keys.
- **`CheckInWorker`:** daily. On the first day of a new period, if income isn't confirmed, notify.
- **`BackupWorker`:** periodic per settings.
- **Widget refresh:** after writes, plus a 1-hour Glance periodic fallback.

## 6. Key flows (sequence)

**Quick add save:**
1. Keypad expression → `AmountExpression.evaluate`, which must be > 0.
2. Tap category → `TransactionRepository.add(...)`.
3. In a single DB transaction: insert, upsert merchant rule if a merchant is present.
4. After commit: `BudgetAlerts.evaluate(period)`, `WidgetUpdater.refresh()`.
5. The UI emits a one-off `Saved(txId)` event, which shows the snackbar with Undo → `delete(txId)`.

**Capture:**
1. `onNotificationPosted`: filter on package + not ongoing + not group summary.
2. `CaptureParser.parse(source, title, text, postedAt)` → `Parsed | Unparsed`.
3. Dedupe.
4. Insert capture.
5. Rule lookup. If `autoAdd` is on and a rule exists, add the transaction and post an "Added · Undo" notification. Otherwise post a categorize notification with actions.
6. Action handling goes through a `BroadcastReceiver` → repository.

**Backup:**
1. Authorize silently, or with a resolution in the UI.
2. Build the archive in a cache dir: `VACUUM INTO`, then settings JSON, then manifest.
3. Upload multipart to appDataFolder.
4. Prune to 2 backups.
5. Store lastAt/size.

**Restore:**
1. List the appData backups and pick the newest.
2. Download to cache.
3. Validate the manifest (format version known, schema ≤ current).
4. Close the DB, swap the files, write settings.
5. Restart the process.

## 7. Error handling
- **Validation lives in the domain layer:** amount > 0, a name is required, cycleCount ≥ 1, startDay 1–28. Errors surface as field errors, never as silent no-ops.
- **Drive:**
  - Network or 5xx errors retry with backoff (WorkManager).
  - 401 → re-authorize once.
  - Consent revoked → notification.
  - An unconfigured OAuth client (`DEVELOPER_ERROR`) → an explicit "not configured" UI state.
- **Restore** never deletes the current DB until the new one has validated and been fully written. The old one is kept as `grid.db.bak` until the next successful launch.
- **Parsers** never throw. An unknown format becomes `Unparsed`, which is logged to diagnostics if enabled.
- **Workers** are idempotent: re-running a day produces no duplicates (charges keyed by subscriptionId + chargeDay).

## 8. Privacy & security
- No internet permission is used except for Drive calls. No analytics or crash reporting SDKs.
- The notification listener ignores every package except the 3 payment apps. The raw text kept is limited to diagnostics (local, 30 days, clearable).
- `allowBackup=false`: Drive backup is the explicit, user-controlled channel. This avoids silent Google auto-backup of financial data, which Jules's draft had.
- Optional app lock. Hide-amounts mode for use in public.

## 9. Testing strategy
- **Pure unit tests (JVM):**
  - MoneyFormat, AmountExpression, BudgetPeriods, BillingSchedule (month-end clamping, catch-up)
  - InsightsEngine
  - every capture parser, with a sample corpus per source and locale
  - backup manifest validation, merchant normalization
- **Robolectric:**
  - Room DAOs and repositories (seeding, aggregation queries, cascade rules)
  - migration tests from v1 as schemas evolve
  - DataStore settings
  - BillsWorker with `TestListenableWorkerBuilder` and a fixed Clock
- **ViewModel tests** with fake repositories plus Turbine.
- **End-to-end on the emulator** via `scripts/` (adb + uiautomator). Each milestone is driven through its flows, with light and dark screenshots saved for review.
- **CI:** a GitHub Actions workflow runs `assembleDebug testDebugUnitTest lintDebug` on push/PR.

## 10. Milestones
Each milestone ends with: a green build, tests passing, flows verified on the emulator with screenshots, and a commit on `v2`.

| # | Milestone | Content |
|---|---|---|
| M0 | Foundation | Gradle/catalog, design system (theme, fonts, components, grid background), nav shell with bottom bar, CI, `.gitignore`, scripts |
| M1 | Core ledger | DB + seeds, settings, onboarding, quick add, home, activity, check-in |
| M2 | Bills | Subscriptions, pending payments, BillsWorker, reminders |
| M3 | Insights | Charts, insights engine, budgets, alerts |
| M4 | Detection | Listener, parsers, inbox, notifications with actions, enable flow, diagnostics |
| M5 | Backup | Drive client, archive, auto-backup worker, restore, local SAF backup, CSV |
| M6 | Polish | Widget, QS tile, shortcuts, app lock, hide amounts, icon, splash, full light/dark QA |
| M7 | Ship | README, docs, replace `main` with v2, close PR #1, tag Jules's draft as `jules-draft` |

## 11. Tooling delivered alongside
- `scripts/`: build / run / logs / screenshot / test / ui automation / sha1 (for Drive OAuth setup).
- `scripts/jules.ps1` + `docs/JULES.md`: the bridge to Jules (REST API v1alpha with `JULES_API_KEY`: list sources, create sessions, send messages, read activities, approve plans), plus the PR-comment channel. This keeps Jules reachable if the owner brings it back.

## 12. Risks
| Risk | Mitigation |
|---|---|
| Real notification formats differ from what's assumed | Heuristic parsers + diagnostics log + the owner shares samples → parser tests |
| AGP 9 / KSP / Hilt bleeding-edge friction | Verified in M0 before any feature work; fall back to AGP 8.13 if blocked |
| Drive needs owner-side Google Cloud setup | Clear doc + SHA-1 script; local backup works meanwhile |
| Scope is large | Strict milestones; each one leaves a working app |

## 13. Open questions for the owner (non-blocking)
1. **Currency:** defaults to the phone's locale currency (changeable). Is a single currency OK for v1?
2. **Project checklists:** the old repo description mentions them, but the brief doesn't, so they're left out. Confirm.
3. **Drive setup:** 10 minutes in Google Cloud Console (see `docs/GOOGLE_DRIVE_SETUP.md`).
4. **Real notification samples** from Wallet/PayPal/Revolut, to harden the parsers (the diagnostics log can collect them).
5. **Install path:** USB/adb install avoids Android's sideload restriction on notification access. Is Play internal testing wanted later?
6. **Languages** beyond English?
