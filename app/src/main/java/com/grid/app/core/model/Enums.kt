package com.grid.app.core.model

/** Persisted by name in Room and DataStore — never rename constants without a migration. */

enum class TxType { EXPENSE, INCOME }

enum class TxSource { MANUAL, CAPTURE, SUBSCRIPTION, PENDING, CHECKIN, BANK }

enum class CategoryKind { EXPENSE, INCOME }

enum class PaymentKind { CASH, CARD, GOOGLE_WALLET, PAYPAL, REVOLUT, BANK, OTHER }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class CycleUnit { WEEK, MONTH, YEAR }

enum class SubscriptionStatus { ACTIVE, PAUSED, CANCELLED }

enum class PendingDirection { I_OWE, OWED_TO_ME }

enum class PendingStatus { PENDING, DONE, CANCELLED }

enum class CaptureSource { GOOGLE_WALLET, PAYPAL, REVOLUT }

enum class CaptureDirection { OUT, IN }

enum class CaptureStatus { NEW, ADDED, DISMISSED, UNPARSED }

enum class BankStatus { ACTIVE, EXPIRED, NEEDS_SETUP, ERROR }

/** What a bank transaction is, which decides whether it is booked automatically. */
enum class BankTxKind { CARD_SPEND, DIRECT_DEBIT, TRANSFER_OUT, MONEY_IN, INTERNAL }

enum class BankTxState { BOOKED, NEEDS_DECISION, IGNORED }
