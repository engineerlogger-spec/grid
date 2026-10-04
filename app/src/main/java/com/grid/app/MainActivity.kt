package com.grid.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.grid.app.feature.lock.AppLock
import com.grid.app.feature.lock.LockScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grid.app.core.designsystem.components.LocalHideAmounts
import com.grid.app.core.designsystem.components.LocalMoneyFormatter
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.designsystem.theme.isGridDark
import com.grid.app.core.money.MoneyFormatter
import com.grid.app.core.notify.LaunchTarget
import com.grid.app.navigation.GridAppUi
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    @Inject lateinit var moneyFormatter: MoneyFormatter
    @Inject lateinit var appLock: AppLock

    /** Set from notification taps, widget, tile and shortcuts; consumed once by the UI. */
    private var launchTarget by mutableStateOf<LaunchTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { appViewModel.state.value is AppUiState.Loading }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) launchTarget = LaunchTarget.from(intent)
        appLock.attach()

        setContent {
            val state by appViewModel.state.collectAsStateWithLifecycle()
            val dismissed by appViewModel.checkInDismissed.collectAsStateWithLifecycle()
            val locked by appLock.locked.collectAsStateWithLifecycle()
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
                    GridAppUi(
                        state = ready,
                        checkInDismissed = dismissed,
                        onDismissCheckIn = appViewModel::dismissCheckIn,
                        launchTarget = if (locked) null else launchTarget,
                        onLaunchHandled = { launchTarget = null },
                    )
                    if (locked) LockScreen(onUnlock = ::authenticate)
                }
            }
        }
    }

    private fun authenticate() {
        if (!appLock.canAuthenticate()) { appLock.unlock(); return }
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = appLock.unlock()
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.lock_title))
                .setSubtitle(getString(R.string.lock_body))
                .setAllowedAuthenticators(AppLock.AUTHENTICATORS)
                .build(),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LaunchTarget.from(intent)?.let { launchTarget = it }
    }
}
