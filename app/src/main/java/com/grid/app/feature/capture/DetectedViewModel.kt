package com.grid.app.feature.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.CaptureItem
import com.grid.app.core.data.repo.CaptureRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.model.CaptureDirection
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
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

data class DetectedUiState(
    val loading: Boolean = true,
    val currency: String = "EUR",
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
    categories: CategoryRepository,
    private val processor: CaptureProcessor,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val inboxCards = captures.observeInbox().mapLatest { items ->
        items.map { item -> DetectedCard(item, processor.suggestionsFor(item, captures.ruleFor(item.merchant)?.categoryId, count = 4)) }
    }

    val state: StateFlow<DetectedUiState> = combine(
        settings.settings, inboxCards, captures.observeRecentlyAdded(), captures.observeUnparsed(), categories.observeAll(),
    ) { s, inbox, added, unparsed, cats ->
        DetectedUiState(
            loading = false,
            currency = s.currency,
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

    /** Plain-text dump of unrecognised notifications, for sharing so parsers can be improved. */
    fun diagnosticsText(): String = state.value.unparsed.joinToString("\n\n") { "[${it.source}] ${it.title}\n${it.text}" }
}
