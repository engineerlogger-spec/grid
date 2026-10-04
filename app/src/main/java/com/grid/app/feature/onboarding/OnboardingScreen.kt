package com.grid.app.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.R
import com.grid.app.core.designsystem.components.CapsLabel
import com.grid.app.core.designsystem.components.GridChip
import com.grid.app.core.designsystem.components.HeroTile
import com.grid.app.core.designsystem.components.LocalMoneyFormatter
import com.grid.app.core.designsystem.components.MoneyText
import com.grid.app.core.designsystem.components.MonthGridIllustration
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.model.ThemeMode
import com.grid.app.feature.common.CurrencyPicker
import com.grid.app.feature.common.IncomeLinesEditor
import com.grid.app.feature.common.MoneyField

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.page != OnboardingPage.WELCOME) { viewModel.back() }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.page != OnboardingPage.WELCOME) {
                IconButton(onClick = viewModel::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            }
            Spacer(Modifier.weight(1f))
            PageDots(state.page)
        }
        AnimatedContent(
            targetState = state.page,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
            },
            modifier = Modifier.weight(1f),
            label = "onboarding",
        ) { page ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (page) {
                    OnboardingPage.WELCOME -> Welcome()
                    OnboardingPage.CURRENCY -> {
                        Title(R.string.onb_currency_title, R.string.onb_currency_body)
                        CurrencyPicker(state.currency, viewModel::setCurrency, Modifier.weight(1f))
                    }
                    OnboardingPage.INCOME -> Scrolling {
                        Title(R.string.onb_income_title, R.string.onb_income_body)
                        IncomeLinesEditor(state.lines, state.currency, viewModel::setLines, viewModel::addLine, showActiveToggle = false)
                    }
                    OnboardingPage.GOAL -> Scrolling { GoalPage(state, viewModel) }
                    OnboardingPage.THEME -> Scrolling { ThemePage(state.theme, viewModel::setTheme) }
                    OnboardingPage.READY -> Ready()
                }
            }
        }
        Button(
            onClick = { if (state.page == OnboardingPage.READY) viewModel.finish() else viewModel.next() },
            enabled = state.canContinue && !state.finishing,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                stringResource(
                    when (state.page) {
                        OnboardingPage.WELCOME -> R.string.onb_get_started
                        OnboardingPage.READY -> R.string.onb_start
                        else -> R.string.action_next
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun ColumnScope.Scrolling(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

@Composable
private fun PageDots(page: OnboardingPage) {
    val colors = GridTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OnboardingPage.entries.forEach { p ->
            Box(
                Modifier.size(width = if (p == page) 18.dp else 8.dp, height = 8.dp)
                    .background(if (p.ordinal <= page.ordinal) colors.accentText else colors.hairline, RoundedCornerShape(3.dp)),
            )
        }
    }
}

@Composable
private fun Title(title: Int, body: Int) {
    val colors = GridTheme.colors
    Text(stringResource(title), style = MaterialTheme.typography.headlineLarge, color = colors.text)
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
}

@Composable
private fun ColumnScope.Welcome() {
    val colors = GridTheme.colors
    Spacer(Modifier.weight(0.3f))
    HeroTile(Modifier.fillMaxWidth()) {
        Text("Grid", style = MaterialTheme.typography.displayMedium, color = colors.heroText)
        Spacer(Modifier.height(16.dp))
        MonthGridIllustration()
    }
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.onb_welcome_title), style = MaterialTheme.typography.displaySmall, color = colors.text)
    Text(stringResource(R.string.onb_welcome_body), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
    Spacer(Modifier.weight(0.7f))
}

@Composable
private fun GoalPage(state: OnboardingUiState, vm: OnboardingViewModel) {
    val colors = GridTheme.colors
    val formatter = LocalMoneyFormatter.current
    Title(R.string.onb_goal_title, R.string.onb_goal_body)
    HeroTile(Modifier.fillMaxWidth()) {
        CapsLabel(stringResource(R.string.checkin_goal), color = colors.heroMuted)
        MoneyText(state.goalMinor, state.currency, style = GridText.moneyHero, color = colors.heroText, fractionColor = colors.heroMuted, masked = false)
        if (state.incomeMinor > 0) {
            Text(
                stringResource(R.string.onb_goal_saves, formatter.format((state.incomeMinor - state.goalMinor).coerceAtLeast(0), state.currency)),
                style = MaterialTheme.typography.labelMedium,
                color = colors.accent,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(70, 80, 90).forEach { pct ->
            GridChip(label = "$pct%", selected = state.goalPercent == pct, onClick = { vm.setGoalPercent(pct) })
        }
        GridChip(label = stringResource(R.string.onb_goal_custom), selected = state.goalPercent == null, onClick = { vm.setCustomGoal(state.customGoal) })
    }
    if (state.goalPercent == null) {
        MoneyField(state.customGoal, vm::setCustomGoal, state.currency, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ThemePage(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val colors = GridTheme.colors
    Title(R.string.onb_theme_title, R.string.onb_theme_body)
    listOf(
        Triple(ThemeMode.SYSTEM, R.string.theme_system, Icons.Rounded.PhoneAndroid),
        Triple(ThemeMode.DARK, R.string.theme_dark, Icons.Rounded.DarkMode),
        Triple(ThemeMode.LIGHT, R.string.theme_light, Icons.Rounded.LightMode),
    ).forEach { (mode, label, icon) ->
        val isSelected = mode == selected
        Surface(
            onClick = { onSelect(mode) },
            shape = RoundedCornerShape(20.dp),
            color = colors.tile,
            border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) colors.accentText else colors.hairline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = if (isSelected) colors.accentText else colors.muted)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(label), style = MaterialTheme.typography.titleMedium, color = colors.text)
            }
        }
    }
}

@Composable
private fun ColumnScope.Ready() {
    val colors = GridTheme.colors
    Spacer(Modifier.weight(0.4f))
    MonthGridIllustration(Modifier.padding(end = 120.dp))
    Text(stringResource(R.string.onb_ready_title), style = MaterialTheme.typography.displaySmall, color = colors.text)
    Text(stringResource(R.string.onb_ready_body), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
    Spacer(Modifier.weight(0.6f))
}
