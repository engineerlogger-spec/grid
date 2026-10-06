package com.grid.app.core.notify

/** Notification ids shared by several posters, so one can replace another's notification of the same payment. */
object NotificationIds {
    /** A payment notification's alert ("Added…", "Detected…"); the check after paying replaces it. */
    fun capture(captureId: Long): Int = 80_000 + captureId.toInt()

    /** The check after paying of an entry no notification announced (a direct debit, a transfer). */
    fun paymentCheck(entryId: Long): Int = 90_000 + entryId.toInt()

    /** Several payments listed at once: one summary. */
    const val PAYMENTS_SUMMARY = 7003
}
