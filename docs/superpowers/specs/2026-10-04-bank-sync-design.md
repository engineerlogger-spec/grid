# Grid: bank sync design (M8)

**Date:** 2026-10-04 · **Status:** approved by the owner ("ok let's try it")

## 1. Goal
Sync the owner's spending from Revolut, Google Wallet and PayPal automatically.

The owner's Wallet holds only Revolut cards, and their PayPal is funded by Revolut, so every payment lands on Revolut. **One connector, Revolut via Enable Banking, covers all three platforms.**

| Paid with | Revolut data shows | Grid records |
|---|---|---|
| Revolut card | `STARBUCKS` | method *Revolut* |
| Google Wallet | `STARBUCKS` | method *Google Wallet* if a matching Wallet notification was captured, else *Revolut* |
| PayPal | `PAYPAL *NETFLIX` | merchant *Netflix*, method *PayPal* |

## 2. Why Enable Banking
- Revolut's own Open Banking API is for licensed providers only, and its Business API is for business accounts only.
- GoCardless/Nordigen closed to new sign-ups in 2025.
- Enable Banking is a licensed aggregator with 2,700+ EU banks, Revolut included.
  - **Restricted mode** lets an application read only the accounts its owner links, free and with no contract.
  - That suits a personal, single-user app.
- Limits:
  - Consent lasts up to `maximum_consent_validity`, usually 180 days, and then needs re-approval.
  - Unattended fetches are limited to about 4 a day per account.
- Publishing Grid to other users would need an Enable Banking contract and a backend to hold the key. That is out of scope.

Not built:
- A direct PayPal connector. It isn't needed for coverage, because PayPal is funded by Revolut. `BankConnector` keeps it possible later.
- A Google Wallet connector. No API exists to read Wallet transactions.

## 3. Architecture
```
Revolut ─PSD2─▶ Enable Banking API ◀─HTTPS, RS256 JWT─ EnableBankingClient ─▶ BankSync ─▶ Classifier ─▶ Reconciler ─▶ ledger
                                                             notification captures ──────────────────────┘
```
New package `core/bank` (pure Kotlin, apart from the client and the key store):
- `BankConnector`: the interface `aspsps(country)`, `startAuth(...)`, `createSession(code)`, `transactions(accountUid, from, to)`, `balances(accountUid)`.
- `EnableBankingClient`: OkHttp and kotlinx-serialization against `https://api.enablebanking.com`.
  - Every request carries `Authorization: Bearer <JWT>`.
  - JWT header `{typ: JWT, alg: RS256, kid: <applicationId>}`, claims `{iss: "enablebanking.com", aud: "api.enablebanking.com", iat, exp: iat+3600}`.
  - Signed with `SHA256withRSA`.
- `BankKeyStore`: holds the application ID and private key (a PKCS#8 PEM).
  - Stored as a file encrypted with an AES-GCM key from the Android Keystore.
  - The file is **never in backups**; `data_extraction_rules` already exclude everything.
- `DescriptorCleaner`, a pure function:
  - Strips `PAYPAL *` and similar processor prefixes, and sets the method to PayPal.
  - Removes trailing city, country and reference noise.
  - Title-cases the result.
- `TxClassifier`, a pure function that sorts a bank transaction into one of:
  - `CARD_SPEND` (debit to a merchant)
  - `DIRECT_DEBIT`: a creditor-initiated SEPA debit (utilities, phone, insurance). Detected from `bank_transaction_code` or the descriptor.
  - `TRANSFER_OUT` (debit to a person or IBAN, including standing orders such as rent)
  - `MONEY_IN` (salary, transfers in, refunds)
  - `INTERNAL`: own accounts or pockets, vaults, exchanges, top-ups. Detected from the user's own account identifiers plus Revolut descriptors ("To EUR Vault", "Exchanged to", "Top-Up by").
- `MccCategories`, a pure lookup: merchant category code → seed category (5411 → Groceries, 5812/5814 → Restaurants, 4111/4121/5541 → Transport, 5651/5691 → Clothing…). It's used only when Revolut supplies `merchant_category_code`.
- `Reconciler`: see §5.
- `BankSyncWorker` (WorkManager): runs every 8 hours on any network (3 runs a day, which leaves one of PSD2's ~4 daily fetches for **Sync now**), and runs once immediately after connecting.

## 4. Data (Room schema v2, migration 1→2 with a test)
- `bank_connections`: `id`, `provider`, `aspspName`, `aspspCountry`, `sessionId`, `validUntil`, `status` (ACTIVE / EXPIRED / NEEDS_SETUP / ERROR), `lastSyncAt`, `lastError`, `createdAt`.
- `bank_accounts`:
  - Columns: `id`, `connectionId`, `uid`, `identificationHash`, `name`, `currency`, `ibanMasked`, `enabled`, `syncedThrough`.
  - `uid` changes with each consent. `identificationHash` is stable and re-links accounts after a reconnect.
- `bank_transactions` (staging):
  - Columns: `id`, `accountId`, `externalId` (unique per account), `bookingDate`, `amountMinor`, `currency`, `direction`, `counterparty`, `description`, `mcc`, `kind`, `state` (BOOKED_TO_LEDGER / NEEDS_DECISION / IGNORED), `transactionId` (the ledger link).
  - `externalId` is the first of `transaction_id`, `entry_reference`, or a hash of date|amount|counterparty|description|ordinal.
- `transactions`: a new `needsReview` column (default 0) and a new `TxSource.BANK`.
- Restoring a backup restores these tables. The key is not restored, so the connection shows *Reconnect*.

A ledger entry's date is `transaction_date` when present, else `booking_date`. Only **booked** transactions (`status = BOOK`) are imported. Pending ones are skipped: notification capture already gives real-time entries, and booked data then corrects them.

## 5. Reconciler (each new booked transaction, in order)
1. **INTERNAL** → `IGNORED`.
2. **CARD_SPEND and DIRECT_DEBIT** → book an expense, after checking for duplicates. It is matched against unlinked ledger expenses in the same currency, dated from 1 day before to 5 days after the booking date:
   - A **capture or manual** entry with the same amount and a similar merchant (or none): link it. The bank amount wins, and a Wallet or PayPal method on the capture is kept.
   - A **subscription or pending-payment** entry with an amount within 10% and a similar merchant: link it, and the bank amount wins.
   - Otherwise: create a `BANK` transaction. Its category comes from the merchant rule, then the MCC category; failing both it goes to *Other* with `needsReview = 1`.
3. **TRANSFER_OUT** is booked automatically, without asking, in two cases:
   - It matches a Grid bill (subscription or pending payment) by amount within 10%, a ±3-day window and a similar name. It is linked to that bill, so rent tracked in Bills is never counted twice.
   - Its counterparty has a learned rule (`MerchantKey` of the creditor name, or the creditor IBAN).

   Otherwise it goes to `NEEDS_DECISION`. Review groups these by counterparty ("J. Dupont · 3 × €850"). One tap books the whole group and learns the rule, so every later rent payment is automatic.
4. **MONEY_IN** → `NEEDS_DECISION`, unless a counterparty rule exists. Salary is learned the same way.
5. "Similar merchant" means the `MerchantKey`s share at least one token of 3 or more characters, or either side has no merchant.

## 6. UX (all strings in `strings.xml`, short)
- **Settings → Bank sync** guides setup in 4 steps:
  1. Create a free Enable Banking account (link).
  2. Create an application with the redirect URL shown (copy button), then download its key.
  3. In Enable Banking, link the Revolut account.
  4. Paste the application ID and pick the key file in Grid. Grid checks them by calling `GET /aspsps`.
- **Connecting Revolut:**
  - Pick the country (defaults from the phone's locale).
  - The login opens in a Custom Tab, and Revolut's app approves it.
  - Back in Grid, choose which accounts to sync (default: those in Grid's currency), then how far back to import: this period, 3 months (default) or 12 months.
- **Connection card** in Settings and Home: last sync time, days of consent left, *Sync now* (which counts as a user-present fetch) and *Reconnect*.
- **Review** (the Detected screen gains a *Bank* section):
  - Uncategorised merchants are **grouped**, e.g. "Lidl · 14 payments · €312". One category tap fixes them all and teaches the rule.
  - Transfers out and money in are grouped by counterparty, with *Book as…* (which learns the rule), *Ignore* and *Always ignore*. Salary-like credits are offered to the monthly check-in.
- **Consent reminders:** a notification 7 days and 1 day before expiry. Once expired, Home shows a *Reconnect Revolut* card.

**Redirect URL:** register `grid://bank-callback` if Enable Banking accepts custom schemes. Otherwise use a static `https://engineerlogger-spec.github.io/grid/bank-callback/` page (GitHub Pages) that forwards the query to `grid://bank-callback` and has a *Return to Grid* button. The last resort is pasting the final URL into Grid.

## 7. Errors
| Case | Behaviour |
|---|---|
| 401/403 or a session error | The connection becomes `EXPIRED`, and Grid shows the Reconnect card |
| 429 rate limit | Skip this run and retry at the next window |
| Network or 5xx | WorkManager backs off exponentially; `lastError` is shown on the card |
| Bad key or app ID during setup | An inline error; nothing is saved |
| A transaction in another currency | Only accounts in Grid's currency can be enabled. As a guard, any other currency is staged as `NEEDS_DECISION` and never auto-booked |

## 8. Testing
- **Unit tests:**
  - JWT signing, verified with the matching public key.
  - `EnableBankingClient` against MockWebServer fixtures (aspsps, auth, sessions, paginated transactions with `continuation_key`).
  - `DescriptorCleaner`, `TxClassifier` and `MccCategories` tables.
  - `Reconciler` cases: capture merge, subscription link, manual duplicate, two same-amount coffees, internal transfer, foreign currency.
  - The 1→2 migration on a populated v1 database.
- **Debug-only `DemoBank` connector:** canned Revolut-like history behind a debug Settings entry, so `e2e-m8.ps1` can cover connect, import, grouped review, dedupe against a capture, and reconnect, all without real credentials.
- **Real check:** the owner connects their Revolut once. The Enable Banking sandbox (Mock ASPSP) can also be used if the owner shares a sandbox key.

## 9. Owner actions
1. Create an Enable Banking account and a production application in restricted mode, then link Revolut (about 10 minutes; the in-app guide covers it).
2. Import the app ID and key on the phone. The key never needs to leave the phone or reach this repo.

## 10. Later (not in M8)
- Detect subscriptions from recurring charges ("We found Netflix €13.49 monthly — track it?").
- Refund matching (a credit cancels its original expense).
- Multi-currency pockets.
- A direct PayPal connector for item-level detail.
