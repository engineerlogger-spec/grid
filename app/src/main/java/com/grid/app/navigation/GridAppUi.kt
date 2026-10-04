package com.grid.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.grid.app.AppUiState
import com.grid.app.R
import com.grid.app.core.designsystem.components.BottomBarItem
import com.grid.app.core.designsystem.components.GridBottomBar
import com.grid.app.core.designsystem.components.GridSurface
import com.grid.app.core.designsystem.components.LocalMoneyFormatter
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.feature.activity.ActivityScreen
import com.grid.app.feature.bank.BankSetupScreen
import com.grid.app.feature.bank.MovedScreen
import com.grid.app.core.model.TxType
import com.grid.app.core.notify.LaunchTarget
import com.grid.app.feature.add.QuickAddSheet
import com.grid.app.feature.add.QuickAddViewModel
import com.grid.app.feature.backup.BackupScreen
import com.grid.app.feature.bills.BillsScreen
import com.grid.app.feature.bills.PendingEditorScreen
import com.grid.app.feature.bills.SubscriptionEditorScreen
import com.grid.app.feature.capture.CaptureSetupScreen
import com.grid.app.feature.capture.DetectedScreen
import com.grid.app.feature.checkin.CheckInScreen
import com.grid.app.feature.common.LocalMessenger
import com.grid.app.feature.common.LocalQuickAdd
import com.grid.app.feature.common.Messenger
import com.grid.app.feature.common.QuickAddController
import com.grid.app.feature.home.HomeScreen
import com.grid.app.feature.insights.InsightsScreen
import com.grid.app.feature.onboarding.OnboardingScreen
import com.grid.app.feature.settings.CategoriesScreen
import com.grid.app.feature.settings.SettingsScreen

/** Root of the UI: navigation, bottom bar, quick-add sheet and snackbars. */
@Composable
fun GridAppUi(
    state: AppUiState.Ready,
    checkInDismissed: Boolean,
    onDismissCheckIn: () -> Unit,
    launchTarget: LaunchTarget?,
    onLaunchHandled: () -> Unit,
    quickAdd: QuickAddController = remember { QuickAddController() },
) {
    val nav = rememberNavController()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val messenger = remember { Messenger(snackbarHost, scope) }
    val quickAddVm: QuickAddViewModel = hiltViewModel()
    val formatter = LocalMoneyFormatter.current
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val onboardingDone = state.settings.onboardingDone

    // Leaving onboarding: replace it with Home.
    LaunchedEffect(onboardingDone) {
        if (onboardingDone && destination?.hasRoute<OnboardingRoute>() == true) {
            nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
        }
    }
    // Deep entry from notifications, widget, Quick Settings tile and launcher shortcuts.
    LaunchedEffect(launchTarget, onboardingDone) {
        val target = launchTarget ?: return@LaunchedEffect
        if (!onboardingDone) return@LaunchedEffect
        when (target) {
            LaunchTarget.BILLS -> nav.navigateToTab("bills")
            LaunchTarget.INSIGHTS -> nav.navigateToTab("insights")
            LaunchTarget.CHECK_IN -> nav.navigate(CheckInRoute) { launchSingleTop = true }
            LaunchTarget.ADD_EXPENSE -> quickAdd.add(TxType.EXPENSE)
            LaunchTarget.ADD_INCOME -> quickAdd.add(TxType.INCOME)
            LaunchTarget.DETECTED -> nav.navigate(DetectedRoute) { launchSingleTop = true }
            LaunchTarget.BACKUP -> nav.navigate(BackupRoute) { launchSingleTop = true }
            LaunchTarget.BANK -> nav.navigate(BankSetupRoute) { launchSingleTop = true }
        }
        onLaunchHandled()
    }
    // Monthly income check-in gate.
    LaunchedEffect(state.needsCheckIn, checkInDismissed, onboardingDone) {
        if (onboardingDone && state.needsCheckIn && !checkInDismissed && destination?.hasRoute<CheckInRoute>() != true) {
            nav.navigate(CheckInRoute) { launchSingleTop = true }
        }
    }

    val tabs = listOf(
        BottomBarItem("home", stringResource(R.string.nav_home), Icons.Outlined.SpaceDashboard, Icons.Rounded.SpaceDashboard),
        BottomBarItem("activity", stringResource(R.string.nav_activity), Icons.Outlined.ReceiptLong, Icons.Rounded.ReceiptLong),
        BottomBarItem("bills", stringResource(R.string.nav_bills), Icons.Outlined.CalendarMonth, Icons.Rounded.CalendarMonth),
        BottomBarItem("insights", stringResource(R.string.nav_insights), Icons.Outlined.DonutLarge, Icons.Rounded.DonutLarge),
    )
    val selectedTab = when {
        destination?.hasRoute<HomeRoute>() == true -> "home"
        destination?.hasRoute<ActivityRoute>() == true -> "activity"
        destination?.hasRoute<BillsRoute>() == true -> "bills"
        destination?.hasRoute<InsightsRoute>() == true -> "insights"
        else -> null
    }
    val savedText = stringResource(R.string.add_saved, "%1\$s", "%2\$s")
    val updatedText = stringResource(R.string.add_updated)
    val deletedText = stringResource(R.string.add_deleted, "%1\$s")
    val undo = stringResource(R.string.action_undo)

    CompositionLocalProvider(LocalQuickAdd provides quickAdd, LocalMessenger provides messenger) {
        GridSurface {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0),
                bottomBar = {
                    if (selectedTab != null) {
                        GridBottomBar(
                            items = tabs,
                            selectedKey = selectedTab,
                            onSelect = { item -> nav.navigateToTab(item.key) },
                            onAdd = { quickAdd.add() },
                            addLabel = stringResource(R.string.nav_add),
                        )
                    }
                },
                snackbarHost = {
                    SnackbarHost(snackbarHost) { data ->
                        Snackbar(
                            snackbarData = data,
                            shape = RoundedCornerShape(16.dp),
                            containerColor = GridTheme.colors.hero,
                            contentColor = GridTheme.colors.heroText,
                            actionColor = GridTheme.colors.accent,
                        )
                    }
                },
            ) { padding ->
                NavHost(
                    navController = nav,
                    startDestination = if (onboardingDone) HomeRoute else OnboardingRoute,
                    modifier = Modifier,
                ) {
                    composable<OnboardingRoute> { OnboardingScreen(onRestore = { nav.navigate(BackupRoute) }) }
                    composable<HomeRoute> {
                        HomeScreen(
                            contentPadding = padding,
                            onOpenSettings = { nav.navigate(SettingsRoute) },
                            onOpenActivity = { day -> nav.navigate(ActivityRoute(dayEpoch = day)) },
                            onOpenCheckIn = { nav.navigate(CheckInRoute) { launchSingleTop = true } },
                            onOpenBills = { nav.navigateToTab("bills") },
                            onOpenDetected = { nav.navigate(DetectedRoute) },
                            onOpenBank = { nav.navigate(BankSetupRoute) },
                            onOpenMoved = { nav.navigate(MovedRoute) },
                        )
                    }
                    composable<ActivityRoute> { ActivityScreen(contentPadding = padding) }
                    composable<BillsRoute> {
                        BillsScreen(
                            contentPadding = padding,
                            onEditSubscription = { id -> nav.navigate(SubscriptionEditRoute(id)) },
                            onEditPending = { id -> nav.navigate(PendingEditRoute(id)) },
                        )
                    }
                    composable<SubscriptionEditRoute> { SubscriptionEditorScreen(onDone = { nav.popBackStack() }) }
                    composable<PendingEditRoute> { PendingEditorScreen(onDone = { nav.popBackStack() }) }
                    composable<InsightsRoute> {
                        InsightsScreen(contentPadding = padding, onOpenCategory = { id -> nav.navigate(ActivityRoute(categoryId = id)) })
                    }
                    composable<CheckInRoute> {
                        CheckInScreen(
                            onDone = { nav.popBackStack() },
                            onLater = { onDismissCheckIn(); nav.popBackStack() },
                        )
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(
                            onBack = { nav.popBackStack() }, onOpenCapture = { nav.navigate(CaptureSetupRoute) }, onOpenBackup = { nav.navigate(BackupRoute) },
                            onOpenCategories = { nav.navigate(CategoriesRoute) }, onOpenBank = { nav.navigate(BankSetupRoute) },
                        )
                    }
                    composable<BankSetupRoute> { BankSetupScreen(onBack = { nav.popBackStack() }) }
                    composable<MovedRoute> { MovedScreen(onBack = { nav.popBackStack() }) }
                    composable<DetectedRoute> { DetectedScreen(onBack = { nav.popBackStack() }) }
                    composable<CaptureSetupRoute> { CaptureSetupScreen(onBack = { nav.popBackStack() }) }
                    composable<BackupRoute> { BackupScreen(onBack = { nav.popBackStack() }) }
                    composable<CategoriesRoute> { CategoriesScreen(onBack = { nav.popBackStack() }) }
                }
            }
            quickAdd.request?.let { request ->
                QuickAddSheet(
                    request = request,
                    viewModel = quickAddVm,
                    onDismiss = quickAdd::close,
                    onSaved = { e ->
                        val message = if (e.wasEdit) updatedText
                        else savedText.replace("%1\$s", formatter.format(e.amountMinor, e.currency)).replace("%2\$s", e.categoryName)
                        if (e.wasEdit) messenger.show(message) else messenger.show(message, undo) { quickAddVm.undoSave(e.txId) }
                    },
                    onDeleted = { e ->
                        messenger.show(deletedText.replace("%1\$s", formatter.format(e.tx.amountMinor, e.tx.currency)), undo) { quickAddVm.undoDelete(e.tx) }
                    },
                )
            }
        }
    }
}

private fun NavHostController.navigateToTab(key: String) {
    val route: Any = when (key) {
        "home" -> HomeRoute
        "activity" -> ActivityRoute()
        "bills" -> BillsRoute
        else -> InsightsRoute
    }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
