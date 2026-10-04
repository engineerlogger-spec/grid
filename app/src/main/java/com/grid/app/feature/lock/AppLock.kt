package com.grid.app.feature.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.di.AppScope
import com.grid.app.core.time.AppClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional app lock. Locks on a cold start and after [RELOCK_AFTER_MS] in the background, only when
 * the setting is on and the phone actually has a screen lock (otherwise the user could be locked out).
 */
@Singleton
class AppLock @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsRepository,
    private val clock: AppClock,
    @AppScope scope: CoroutineScope,
) : DefaultLifecycleObserver {

    private val lockRequested = MutableStateFlow(true)
    private var backgroundAt: Long? = null

    val locked: StateFlow<Boolean> = combine(lockRequested, settings.settings) { requested, s -> requested && s.appLock && canAuthenticate() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Must be called on the main thread (MainActivity does it on creation). */
    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundAt = clock.millis()
    }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundAt ?: return
        if (clock.millis() - since > RELOCK_AFTER_MS) lockRequested.value = true
    }

    fun unlock() {
        lockRequested.value = false
    }

    fun canAuthenticate(): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    companion object {
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        private const val RELOCK_AFTER_MS = 60_000L
    }
}
