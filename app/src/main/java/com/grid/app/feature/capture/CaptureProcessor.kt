package com.grid.app.feature.capture

import com.grid.app.core.capture.CaptureParse
import com.grid.app.core.capture.CaptureParsers
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.CaptureItem
import com.grid.app.core.data.repo.CaptureRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.CaptureSource
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.TxType
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** How a payment notification is surfaced to the user (notifications in the app, a fake in tests). */
interface CaptureAlerts {
    /** Auto-added from a learned merchant rule — offer Undo. */
    fun added(capture: CaptureItem, categoryName: String)
    /** Needs a decision — offer the predicted categories and Review. */
    fun detected(capture: CaptureItem, suggestions: List<Category>)
}

enum class CaptureOutcome { DISABLED, IGNORED, UNPARSED, DUPLICATE, AUTO_ADDED, QUEUED }

/**
 * Parses a payment notification and decides what to do with it. Auto-adds only when it's safe:
 * a merchant the user already categorised, money going out, in the app's currency.
 */
@Singleton
class CaptureProcessor @Inject constructor(
    private val captures: CaptureRepository,
    private val categories: CategoryRepository,
    private val transactions: TransactionRepository,
    private val settings: SettingsRepository,
    private val alerts: CaptureAlerts,
) {
    suspend fun process(source: CaptureSource, title: String, text: String, postedAt: Long): CaptureOutcome {
        val s = settings.settings.first()
        if (!s.capture.enabled(source)) return CaptureOutcome.DISABLED
        return when (val parsed = CaptureParsers.parse(source, title, text, s.currency)) {
            is CaptureParse.Ignored -> CaptureOutcome.IGNORED
            CaptureParse.Unparsed -> {
                if (s.capture.diagnostics) captures.recordUnparsed(source, title, text, postedAt)
                CaptureOutcome.UNPARSED
            }
            is CaptureParse.Parsed -> {
                val item = captures.recordParsed(source, title, text, postedAt, parsed) ?: return CaptureOutcome.DUPLICATE
                val rule = captures.ruleFor(item.merchant)
                val canAutoAdd = s.capture.autoAdd && rule != null && item.direction == CaptureDirection.OUT && item.currency == s.currency
                if (canAutoAdd) {
                    captures.accept(item.id, rule!!.categoryId, rule.paymentMethodId)
                    alerts.added(item, categories.get(rule.categoryId)?.name.orEmpty())
                    CaptureOutcome.AUTO_ADDED
                } else {
                    alerts.detected(item, suggestionsFor(item, rule?.categoryId))
                    CaptureOutcome.QUEUED
                }
            }
        }
    }

    /** The learned category first, then the user's most-used categories of the right kind. */
    suspend fun suggestionsFor(item: CaptureItem, learned: Long? = null, count: Int = 2): List<Category> {
        val kind = if (item.direction == CaptureDirection.IN) CategoryKind.INCOME else CategoryKind.EXPENSE
        val type = if (kind == CategoryKind.INCOME) TxType.INCOME else TxType.EXPENSE
        val ordered = categories.orderedByUsage(kind, transactions.categoryUsage(type))
        val first = learned?.let { id -> ordered.firstOrNull { it.id == id } }
        return (listOfNotNull(first) + ordered.filter { it.id != first?.id }).take(count)
    }
}
