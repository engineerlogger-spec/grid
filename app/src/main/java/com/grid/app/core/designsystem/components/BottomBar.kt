package com.grid.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.grid.app.core.designsystem.theme.GridTheme

data class BottomBarItem(val key: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** Bottom navigation with a raised square "+" in the middle — the fastest path to adding a spend. */
@Composable
fun GridBottomBar(
    items: List<BottomBarItem>,
    selectedKey: String?,
    onSelect: (BottomBarItem) -> Unit,
    onAdd: () -> Unit,
    addLabel: String,
    modifier: Modifier = Modifier,
) {
    require(items.size == 4) { "Grid's bar has four destinations around the add button" }
    val colors = GridTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.32f to colors.background.copy(alpha = 0.96f), 1f to colors.background))
            .navigationBarsPadding()
            .padding(top = 18.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            Item(items[0], selectedKey, onSelect)
            Item(items[1], selectedKey, onSelect)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Surface(
                    onClick = onAdd,
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(56.dp)
                        .shadow(if (colors.isDark) 14.dp else 6.dp, RoundedCornerShape(18.dp), ambientColor = colors.accent, spotColor = colors.accent),
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, contentDescription = addLabel, modifier = Modifier.size(30.dp)) }
                }
            }
            Item(items[2], selectedKey, onSelect)
            Item(items[3], selectedKey, onSelect)
        }
    }
}

@Composable
private fun RowScope.Item(item: BottomBarItem, selectedKey: String?, onSelect: (BottomBarItem) -> Unit) {
    val colors = GridTheme.colors
    val selected = item.key == selectedKey
    val tint = if (selected) colors.text else colors.muted
    Column(
        modifier = Modifier
            .weight(1f)
            .clickableNoIndication { onSelect(item) }
            .semantics { this.selected = selected; role = Role.Tab }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .background(if (selected) colors.accent.copy(alpha = if (colors.isDark) 0.16f else 0.5f) else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null, tint = if (selected && colors.isDark) colors.accent else tint, modifier = Modifier.size(22.dp))
        }
        Text(item.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}
