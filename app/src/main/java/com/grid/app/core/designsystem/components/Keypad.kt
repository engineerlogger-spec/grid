package com.grid.app.core.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.designsystem.theme.SpaceGrotesk
import com.grid.app.core.money.KeypadKey

/** Calculator keypad for amounts: digits, ., 00, + and −, backspace (long-press clears) and Save. */
@Composable
fun Keypad(
    onKey: (KeypadKey) -> Unit,
    onSave: () -> Unit,
    saveLabel: String,
    modifier: Modifier = Modifier,
    decimalEnabled: Boolean = true,
    saveEnabled: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Digit(1, onKey); Digit(2, onKey); Digit(3, onKey)
            Key(onClick = { onKey(KeypadKey.Backspace) }, onLongClick = { onKey(KeypadKey.Clear) }, label = "Delete", muted = true) {
                Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = null, modifier = Modifier.height(20.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Digit(4, onKey); Digit(5, onKey); Digit(6, onKey)
            Key(onClick = { onKey(KeypadKey.Plus) }, label = "Plus", muted = true) { KeyText("+") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Digit(7, onKey); Digit(8, onKey); Digit(9, onKey)
            Key(onClick = { onKey(KeypadKey.Minus) }, label = "Minus", muted = true) { KeyText("−") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key(onClick = { onKey(KeypadKey.Dot) }, label = "Decimal point", enabled = decimalEnabled) { KeyText(".") }
            Digit(0, onKey)
            Key(onClick = { onKey(KeypadKey.DoubleZero) }, label = "Double zero") { KeyText("00") }
            Key(
                onClick = onSave,
                label = saveLabel,
                enabled = saveEnabled,
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text(saveLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun RowScope.Digit(d: Int, onKey: (KeypadKey) -> Unit) =
    Key(onClick = { onKey(KeypadKey.Digit(d)) }, label = d.toString()) { KeyText(d.toString()) }

@Composable
private fun KeyText(text: String) =
    Text(text, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 22.sp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.Key(
    onClick: () -> Unit,
    label: String,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    muted: Boolean = false,
    container: Color = GridTheme.colors.raised,
    content: Color = if (muted) GridTheme.colors.muted else GridTheme.colors.text,
    body: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .weight(1f)
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .alpha(if (enabled) 1f else 0.35f)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = label,
                onLongClick = onLongClick?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.VirtualKey); onClick() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides content) { body() }
    }
}
