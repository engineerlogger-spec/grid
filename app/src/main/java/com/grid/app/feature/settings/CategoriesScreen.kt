package com.grid.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.grid.app.R
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.CategoryRepository
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.CategoryBadge
import com.grid.app.core.designsystem.components.LocalMoneyFormatter
import com.grid.app.core.designsystem.components.Segmented
import com.grid.app.core.designsystem.components.Tile
import com.grid.app.core.designsystem.icons.CategoryIcons
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.feature.common.ColorPicker
import com.grid.app.feature.common.FormSection
import com.grid.app.feature.common.GridTextField
import com.grid.app.feature.common.MoneyField
import com.grid.app.feature.common.moneyFieldText
import com.grid.app.feature.common.parseMoney
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoriesUiState(val currency: String = "EUR", val all: List<Category> = emptyList())

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val categories: CategoryRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<CategoriesUiState> = combine(categories.observeAll(), settings.settings) { all, s -> CategoriesUiState(s.currency, all) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    fun save(existing: Category?, kind: CategoryKind, name: String, iconKey: String, colorKey: String, limitMinor: Long?) = viewModelScope.launch {
        if (existing == null) {
            val id = categories.add(name, iconKey, colorKey, kind)
            if (limitMinor != null) categories.setMonthlyLimit(id, limitMinor)
        } else {
            categories.update(existing.copy(name = name.trim(), iconKey = iconKey, colorKey = colorKey, monthlyLimitMinor = limitMinor?.takeIf { it > 0 }))
        }
    }

    fun remove(category: Category) = viewModelScope.launch { categories.remove(category) }
    fun restore(category: Category) = viewModelScope.launch { categories.update(category.copy(archived = false)) }

    fun move(list: List<Category>, index: Int, delta: Int) = viewModelScope.launch {
        val target = index + delta
        if (target !in list.indices) return@launch
        categories.reorder(list.toMutableList().apply { add(target, removeAt(index)) })
    }
}

/** Add, rename, re-icon, recolor, reorder, budget and archive categories. */
@Composable
fun CategoriesScreen(onBack: () -> Unit, viewModel: CategoriesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var kind by rememberSaveable { mutableStateOf(CategoryKind.EXPENSE) }
    var editing by remember { mutableStateOf<Category?>(null) }
    var creating by remember { mutableStateOf(false) }
    val colors = GridTheme.colors
    val formatter = LocalMoneyFormatter.current
    val active = state.all.filter { it.kind == kind && !it.archived }.sortedBy { it.position }
    val archived = state.all.filter { it.kind == kind && it.archived }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                Text(stringResource(R.string.settings_categories), style = MaterialTheme.typography.headlineMedium, color = colors.text, modifier = Modifier.weight(1f))
                Surface(onClick = { creating = true }, shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.categories_add), modifier = Modifier.padding(8.dp))
                }
            }
        }
        item {
            Segmented(
                options = CategoryKind.entries, selected = kind,
                label = { stringResource(if (it == CategoryKind.EXPENSE) R.string.add_expense else R.string.add_income) },
                onSelect = { kind = it }, modifier = Modifier.fillMaxWidth(),
            )
        }
        itemsIndexed(active, key = { _, c -> c.id }) { index, c ->
            Tile(onClick = { editing = c }, contentPadding = PaddingValues(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(c.iconKey, c.colorKey, size = 36.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall, color = colors.text)
                        c.monthlyLimitMinor?.let {
                            Text(stringResource(R.string.category_budget_value, formatter.format(it, state.currency)), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        }
                    }
                    IconButton(onClick = { viewModel.move(active, index, -1) }, enabled = index > 0) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.category_move_up), tint = if (index > 0) colors.muted else colors.faint)
                    }
                    IconButton(onClick = { viewModel.move(active, index, 1) }, enabled = index < active.lastIndex) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.category_move_down), tint = if (index < active.lastIndex) colors.muted else colors.faint)
                    }
                }
            }
        }
        if (archived.isNotEmpty()) {
            item { CapsLabel(stringResource(R.string.category_archived), Modifier.padding(start = 4.dp, top = 12.dp)) }
            itemsIndexed(archived, key = { _, c -> "a${c.id}" }) { _, c ->
                Tile(onClick = { viewModel.restore(c) }, modifier = Modifier.alpha(0.6f), contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryBadge(c.iconKey, c.colorKey, size = 30.dp)
                        Text(c.name, style = MaterialTheme.typography.bodyMedium, color = colors.text, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        Text(stringResource(R.string.category_restore), style = MaterialTheme.typography.labelLarge, color = colors.accentText)
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        CategoryEditor(
            existing = editing, kind = editing?.kind ?: kind, currency = state.currency,
            takenNames = state.all.filter { it.kind == (editing?.kind ?: kind) && !it.archived && it.id != editing?.id }.map { it.name.trim().lowercase() }.toSet(),
            onSave = { name, icon, color, limit -> viewModel.save(editing, editing?.kind ?: kind, name, icon, color, limit); editing = null; creating = false },
            onRemove = editing?.let { c -> { viewModel.remove(c); editing = null } },
            onDismiss = { editing = null; creating = false },
        )
    }
}

@Composable
private fun CategoryEditor(
    existing: Category?,
    kind: CategoryKind,
    currency: String,
    takenNames: Set<String>,
    onSave: (String, String, String, Long?) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = GridTheme.colors
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var icon by remember { mutableStateOf(existing?.iconKey ?: "other") }
    var color by remember { mutableStateOf(existing?.colorKey ?: "slate") }
    var limit by remember { mutableStateOf(moneyFieldText(existing?.monthlyLimitMinor, currency)) }
    var showErrors by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(icon, color, size = 52.dp)
                Spacer(Modifier.size(14.dp))
                val duplicate = name.trim().lowercase() in takenNames
                Column {
                    GridTextField(name, { name = it }, stringResource(R.string.category_name), isError = (showErrors && name.isBlank()) || duplicate)
                    if (duplicate) Text(stringResource(R.string.category_duplicate), style = MaterialTheme.typography.bodySmall, color = colors.danger, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                }
            }
            FormSection(stringResource(R.string.category_icon)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryIcons.all.keys.forEach { key ->
                        val selected = key == icon
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (selected) colors.category(color).container else colors.raised)
                                .then(if (selected) Modifier.border(1.5.dp, colors.category(color).fg, RoundedCornerShape(12.dp)) else Modifier)
                                .clickable { icon = key },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(CategoryIcons.of(key), contentDescription = key, tint = if (selected) colors.category(color).fg else colors.muted, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
            FormSection(stringResource(R.string.sub_color)) { ColorPicker(color) { color = it } }
            if (kind == CategoryKind.EXPENSE) {
                FormSection(stringResource(R.string.category_budget)) { MoneyField(limit, { limit = it }, currency, modifier = Modifier.fillMaxWidth()) }
            }
            Button(
                onClick = { if (name.isBlank() || name.trim().lowercase() in takenNames) showErrors = true else onSave(name, icon, color, parseMoney(limit, currency)) },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
            ) { Text(stringResource(R.string.action_save)) }
            if (onRemove != null) {
                OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(R.string.category_archive), color = colors.danger)
                }
            }
        }
    }
}
