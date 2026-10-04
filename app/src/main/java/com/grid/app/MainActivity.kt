package com.grid.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.core.designsystem.components.LocalHideAmounts
import com.grid.app.core.designsystem.components.LocalMoneyFormatter
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.designsystem.theme.isGridDark
import com.grid.app.core.money.MoneyFormatter
import com.grid.app.navigation.GridAppUi
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    @Inject lateinit var moneyFormatter: MoneyFormatter

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { appViewModel.state.value is AppUiState.Loading }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val state by appViewModel.state.collectAsStateWithLifecycle()
            val dismissed by appViewModel.checkInDismissed.collectAsStateWithLifecycle()
            val ready = state as? AppUiState.Ready ?: return@setContent
            val settings = ready.settings
            GridTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                val dark = isGridDark(settings.themeMode)
                // Status/navigation bar icons follow the app theme, not the system one.
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                        navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    )
                    onDispose {}
                }
                CompositionLocalProvider(
                    LocalMoneyFormatter provides moneyFormatter,
                    LocalHideAmounts provides settings.hideAmounts,
                ) {
                    GridAppUi(state = ready, checkInDismissed = dismissed, onDismissCheckIn = appViewModel::dismissCheckIn)
                }
            }
        }
    }
}
