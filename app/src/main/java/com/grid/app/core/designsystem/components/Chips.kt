package com.grid.app.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grid.app.core.designsystem.theme.GridTheme

/** Pill chip used for filters and quick-add options. */
@Composable
fun GridChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = GridTheme.colors
    val container = if (selected) colors.accent.copy(alpha = if (colors.isDark) 0.16f else 0.55f).compositeOver(colors.tile) else colors.tile
    val content = if (selected) (if (colors.isDark) colors.accent else colors.text) else colors.text
    val border = if (selected) (if (colors.isDark) colors.accent.copy(alpha = 0.6f) else colors.text.copy(alpha = 0.3f)) else colors.hairline
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> leading()
                icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Two-to-four option segmented control (Expense / Income, theme choice…). */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridTheme.colors
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = colors.raised, border = BorderStroke(1.dp, colors.hairline)) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(11.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else colors.raised,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else colors.muted,
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(vertical = 9.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
