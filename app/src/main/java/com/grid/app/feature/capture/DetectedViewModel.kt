package com.grid.app.feature.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.data.db.Seed
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.data.repo.BankReviewGroup
import com.grid.app.core.data.repo.CaptureItem
import com.grid.app.core.data.repo.CaptureRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.data.repo.ReviewKind
import com.grid.app.core.data.repo.SubscriptionRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.model.MerchantKey
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.TxType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetectedCard(val item: CaptureItem, val suggestions: List<Category>)

/** A group of bank items settled with one tap. */
data class BankGroupCard(val group: BankReviewGroup, val suggestions: List<Category>)

data class DetectedUiState(
    val loading: Boolean = true,
    val currency: String = "EUR",
    val bankGroups: List<BankGroupCard> = emptyList(),
    val inbox: List<DetectedCard> = emptyList(),
    val recentlyAdded: List<CaptureItem> = emptyList(),
    val unparsed: List<CaptureItem> = emptyList(),
    val expenseCategories: List<Category> = emptyList(),
    val incomeCategories: List<Category> = emptyList(),
)

@HiltViewModel
class DetectedViewModel @Inject constructor(
    settings: SettingsRepository,
    private val captures: CaptureRepository,
    private val categories: CategoryRepository,
    private val processor: CaptureProcessor,
    private val bank: BankRepository,
    private val transactions: TransactionRepository,
    private val subscriptions: SubscriptionRepository,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val inboxCards = captures.observeInbox().mapLatest { items ->
        items.map { item -> DetectedCard(item, processor.suggestionsFor(item, captures.ruleFor(item.merchant)?.categoryId, count = 4)) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val bankCards = bank.observeReviewGroups().mapLatest { groups ->
        val expense = categories.orderedByUsage(CategoryKind.EXPENSE, transactions.categoryUsage(TxType.EXPENSE)).filter { !it.archived && it.iconKey != Seed.ICON_OTHER }
        val income = categories.orderedByUsage(CategoryKind.INCOME, transactions.categoryUsage(TxType.INCOME)).filter { !it.archived }
        val subs = subscriptions.all()
        groups.map { g ->
            val pool = if (g.kind == ReviewKind.DECIDE_IN) income else expense
            // "Netflix" payments when a Netflix subscription exists: offer its category first.
            val hint = subs.firstOrNull { MerchantKey.of(it.name) == MerchantKey.of(g.title) }?.category?.takeIf { it in pool }
            BankGroupCard(g, (listOfNotNull(hint) + pool.filter { it.id != hint?.id }).take(3))
        }
    }

    val state: StateFlow<DetectedUiState> = combine(
        combine(settings.settings, bankCards) { s, b -> s to b }, inboxCards, captures.observeRecentlyAdded(), captures.observeUnparsed(), categories.observeAll(),
    ) { (s, bankGroups), inbox, added, unparsed, cats ->
        DetectedUiState(
            loading = false,
            currency = s.currency,
            bankGroups = bankGroups,
            inbox = inbox,
            recentlyAdded = added,
            unparsed = unparsed,
            expenseCategories = cats.filter { it.kind == CategoryKind.EXPENSE && !it.archived },
            incomeCategories = cats.filter { it.kind == CategoryKind.INCOME && !it.archived },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetectedUiState())

    fun accept(item: CaptureItem, category: Category) = viewModelScope.launch { captures.accept(item.id, category.id) }
    fun dismiss(item: CaptureItem) = viewModelScope.launch { captures.dismiss(item.id) }
    fun undo(item: CaptureItem) = viewModelScope.launch { captures.undo(item.id) }
    fun clearDiagnostics() = viewModelScope.launch { captures.clearDiagnostics() }

    fun categoriesFor(item: CaptureItem): List<Category> =
        if (item.direction == CaptureDirection.IN) state.value.incomeCategories else state.value.expenseCategories

    fun categoriesFor(group: BankReviewGroup): List<Category> =
        if (group.kind == ReviewKind.DECIDE_IN) state.value.incomeCategories else state.value.expenseCategories

    /** Categorises (already booked) or books (waiting) the whole group; either way the merchant is learned. */
    fun resolve(group: BankReviewGroup, category: Category) = viewModelScope.launch {
        if (group.kind == ReviewKind.CATEGORISE) bank.categorise(group, category.id) else bank.book(group, category.id)
    }

    fun ignore(group: BankReviewGroup, always: Boolean) = viewModelScope.launch { bank.ignore(group, always) }

    /** Plain-text dump of unrecognised notifications, for sharing so parsers can be improved. */
    fun diagnosticsText(): String = state.value.unparsed.joinToString("\n\n") { "[${it.source}] ${it.title}\n${it.text}" }
}
