# Grid v2: M4 (Payment detection) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Executed inline; parsers are test-first.

**Goal:** Turn Google Wallet / PayPal / Revolut payment notifications into one-tap (or automatic) transactions, entirely on-device (spec §3.9).

**Architecture:**
- `core/capture/AmountParser` (pure) finds money in free text across locales.
- `core/capture/CaptureParsers` (pure) applies per-app heuristics and returns `Parsed | Ignored | Unparsed`.
- `PaymentCaptureService` (NotificationListenerService) hands notifications to `CaptureProcessor`. The processor dedupes, stores, auto-adds known merchants, and notifies with category actions.
- `CaptureActionReceiver` handles notification actions.
- UI pieces:
  - a Detected inbox
  - a Home tile
  - a setup screen (permission plus the restricted-settings guidance)
  - Settings toggles
  - a diagnostics log of unrecognised notifications, shareable so parsers can be tuned against real data

## Tasks

### 4.1 AmountParser (TDD)
- **Symbols:** € $ £ ¥ ₹ ₺ ₽ ₩ zł Kč R$ A$ C$ CHF kr, and DH/Dhs for MAD.
- **Codes:** any 3-letter ISO code next to a number.
- **Separators:** decimal by the last-separator rule, grouping with spaces/NBSP/NNBSP/`.`/`,`/`'`.
- Ambiguous `$` and `kr` resolve to the default currency when it uses that symbol.
- Numbers without a currency are ignored, which drops card digits and order numbers.
- A symbol and code for the same amount collapse into one ("€12.99 EUR").

### 4.2 CaptureParsers (TDD)
**Sample corpus per app:**
- **Wallet:** title is the merchant; text is "€4.50 with Visa •••• 1234" or "Paid €4.50…".
- **PayPal:** "You sent/paid … to X" (OUT), "You received … from X" (IN).
- **Revolut:** "Paid €4.50 at Starbucks", the merchant title + "€4.50" form, "Received … from X", refunds.

**Ignored:** declined/failed/reverted payments, top-ups, exchanges, and marketing notifications with no amount.

**Merchant cleanup:** strip emoji and card suffixes, trim punctuation.

### 4.3 Data
- **`CaptureDao`:** insert, get, update, observe by status, `findSimilar` (same source/amount/currency within ±10 min), prune.
- **`CaptureRepository`:** `accept(id, categoryId, methodId?)` creates a CAPTURE transaction (merchant learned) and marks it ADDED. Also `dismiss`, `undo`, `observeInbox`, `observeUnparsed`, `pruneDiagnostics`.
- **Settings:** per-source toggles, auto-add, diagnostics.

### 4.4 Processing + service + actions
- `CaptureProcessor.process(source, title, text, postedAt)`.
- **Auto-add** happens only for a known merchant, an outgoing payment, and the app currency.
- **Notifications:**
  - Auto-added: "Added €4.50 · Starbucks → Restaurants" with Undo.
  - Otherwise: "€4.50 at Starbucks" with two predicted-category actions plus Review.
- **Debug builds only:** the service also accepts `com.android.shell` notifications whose title starts with `[Wallet]`, `[PayPal]` or `[Revolut]`. This lets the full pipeline be exercised on an emulator via `adb shell cmd notification post`.

### 4.5 UI
- **Detected inbox:** each card has a source badge, merchant, amount, time, category chips (accepting teaches the rule) and dismiss. A "Not recognised" section shows raw text with a Share button.
- **Home "Detected" tile** when the inbox is non-empty.
- **Setup screen:** open notification access, plus guidance for "Allow restricted settings" with an App info button (sideloaded installs on Android 13+). It also has per-app toggles, auto-add, and diagnostics.
- **Settings entry** with the live status.

### 4.6 Verification
- Unit tests cover the parsers and the processor (fake notifier).
- **E2E:**
  1. Grant listener access via `cmd notification allow_listener`.
  2. Post `[Revolut] Starbucks` / "Paid €4.50 at Starbucks", then check the Detected tile and inbox.
  3. Categorize it as Restaurants, which creates the transaction.
  4. Post the same merchant again: it's auto-added and an "Added" notification posts.
