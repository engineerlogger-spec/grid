package com.grid.app.core.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/** Clickable without ripple, for text-like actions. */
fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

/** Horizontal shake each time [trigger] changes (and is > 0) — used for "enter an amount first". */
fun Modifier.shake(trigger: Int): Modifier = composed {
    val offset = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            for (x in listOf(14f, -12f, 9f, -6f, 3f, 0f)) {
                offset.animateTo(x, spring(stiffness = Spring.StiffnessHigh))
            }
        }
    }
    graphicsLayer { translationX = offset.value }
}

@Composable
internal fun rememberStaggeredAlpha(index: Int, stepMillis: Int = 14): Float {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((index * stepMillis).toLong())
        alpha.animateTo(1f)
    }
    return alpha.value
}
