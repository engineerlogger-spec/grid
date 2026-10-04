package com.grid.app.feature.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.grid.app.R
import com.grid.app.core.designsystem.components.EmptyState
import com.grid.app.core.designsystem.theme.GridTheme

/** Temporary tab content for milestones not built yet (Bills → M2, Insights → M3). */
@Composable
fun ComingSoonScreen(title: String, contentPadding: PaddingValues) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = contentPadding.calculateBottomPadding())) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = GridTheme.colors.text)
        EmptyState(Icons.Rounded.Construction, title, stringResource(R.string.settings_coming_soon))
    }
}
