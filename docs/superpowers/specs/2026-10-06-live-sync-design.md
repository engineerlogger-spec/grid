# Live sync: fresh bank data, payment checks and alerts

**Date:** 2026-10-06. **Status:** approved by the owner in chat ("unlimited syncs… sync after each notification… slide down to refresh"; all four extras chosen).

## Why

Grid syncs with Revolut every 8 hours, and when the app opens if the last sync is more than an hour old. That was built for PSD2's "4 background requests a day".

On 2026-10-06 a live test ran on a fresh consent of the owner's account. Revolut answered 18 background requests in a row for each of payments, balance and account details, and every request flagged "person present". A 3-hour follow-up is checking whether that changes once the consent is no longer new.

Either way, syncs made while the owner is in the app are exempt by regulation. Background syncs only need a safety net in case Revolut starts refusing them.

## 1. Sync modes

| Mode | Used by | What the bank sees |
|---|---|---|
| **Present** | Pull to refresh, Sync now, opening the app, Ask Grid | The `Psu-Ip-Address` and `Psu-User-Agent` headers, so the request is never counted as background. The phone's public IPv4 comes from ipify and is cached for 5 minutes. |
| **Background** | After a payment notification, every 6 hours | No presence headers. |

- `BankSync.run(mode)` uses `EnableBankingClient.present(presence)` in present mode. If the address can't be found, it falls back to background mode.
- **Pause after a refusal:** if Revolut refuses a background request (HTTP 429), background syncs pause for 6 hours, as Enable Banking advises. The pause end is stored in settings. Present syncs ignore the pause.
- `Presence`, `PresenceProvider` and `EnableBankingClient.present()` come from the unmerged PR #20. The limit-test button from that PR is dropped, and PR #20 is closed.

## 2. Triggers

- **After a payment notification** (Revolut, PayPal or Wallet, payment or reversal): a background sync 5 seconds later (the owner's choice). This is WorkManager unique work with `REPLACE`, so the 2 or 3 notifications of one payment end in one sync, and it requires a network connection.
  - **Retries:** if a payment notified in the last 15 minutes is still not listed by the bank, Grid tries again 30 seconds later, then 2 minutes later, then 5 minutes later, and then stops. "Listed" means a bank row of that exact amount, in the same direction and currency, within 2 days.
- **Opening the app:** a present sync when the last sync is more than 5 minutes old (today: more than 1 hour).
- **Pull to refresh** on Home and Activity: Material 3 `PullToRefreshBox` starts a present sync. A spinner shows while it runs, then a message: "Up to date", "3 new payments" or "Revolut refused, try again later".
- **"Updated 2 min ago"** under the totals on Home and Activity, based on `lastSyncAt`. It reads "Syncing…" while a sync runs.
- **Safety net:** a periodic background sync every 6 hours (today: every 8 hours).

## 3. Real payment times

- **Where the time comes from:** Revolut's `entry_reference` is a UUID whose first 8 hex digits are the Unix second the payment was made. For example, `6ac529a0…` is 17:02:24 UTC, which is the 19:02 shown in the Revolut app.
- **`BankTime.fromId(id, date)`:** returns that instant when it falls on the transaction's date, give or take a day. Otherwise it returns null.
- **New rows:** `occurredAt` is the instant from the id when there is one. Otherwise it's the "day only" marker: 12:00:00.000 local, or 00:00:00.000 for today before noon. This replaces `min(noon, now)`, which could show a fake time.
- **Display:** bank entries show their time unless it is a day-only marker (`BankTime.isDayOnly`).
- **Existing rows:** each time the app opens, a bank row with a timed id gets that time. Its linked entry gets it too, but only if the entry still has the bank's original time. An entry the owner or a notification changed is left alone.

## 4. Pending tag

- An entry is pending while its bank row is still pending: the row's `externalId` starts with `p:` and its state is `BOOKED`.
- `BankDao.observePendingEntryIds()` feeds `Transaction.pending` on Home's recent list and in Activity.
- `TransactionRow` shows "Pending" first in the subtitle, the way it shows "Reverted".
- Pending payments still count in totals, because the money is held.

## 5. Check after paying

After each sync, Grid looks at the payments out that the bank listed for the first time in that sync and that were made in the last 2 hours. For each one, it posts a single notification on a new "After paying" channel:

- **Title:** "Bolt · €23.20".
- **Body:**
  - If the category has a monthly limit: "Transport · €46 left of €200 this month", or "€12 over".
  - Otherwise, if there is a monthly goal: "€320 left to spend this month", using `DashboardCalculator.leftMinor`.
  - Otherwise: "€1,240 spent this month".
- **If a notification alert already exists for the payment** (the entry has a `captureId`), the check reuses that alert's notification id, so it replaces the "Added…" or "Detected…" alert.
- **More than 3 payments in one sync:** one summary instead, "5 new payments · €84.30".

## 6. Bill and odd-charge alerts

These checks also run on the new payments from §5. A finding changes that payment's notification instead of adding another one:

- **Bill paid:** the payment is linked to a subscription, by `subscriptionId` or payee.
  - Title: "SFR paid · €16.99".
  - Body: "usually €14.99", when the amount differs by 5% or more.
- **Bigger than usual:** the payee has at least 3 payments in the last 180 days, and this one is more than 1.5× their median and at least €10 above it.
  - Body: "usually about €15 here".
- **Charged twice?** Another payment to the same payee, for the same amount of at least €5, within 10 minutes, and neither one is reverted.
  - Title: "Charged twice? Bolt €23.20".
  - The €5 floor keeps €1 car-wash tokens out.

Rules live in a pure `PaymentChecks` object, and notification text is built by `PaymentCheckNotifier`.

## Error handling

- **Offline:** WorkManager waits for a network connection. A pull to refresh shows "No connection".
- **ipify unreachable:** the sync runs in background mode, which may be paused.
- **Bank refused:** the sync pauses (background) or shows a message (present).
- **Session expired:** unchanged; Grid shows the existing reconnect notification.

## Testing

Unit tests:
- `BankTime`, using real ids from 6 Oct: `6ac529a0…` → 19:02 Paris, `6ac537f8…` → 20:03.
- Day-only markers.
- `BankSync` modes and the pause, with a scripted connector that returns 429.
- The pending-id flow.
- `PaymentChecks`: bill, bigger-than-usual and double-charge rules, including the car-wash €1 tokens.
- The check-message composition.
- Existing suites stay green.
