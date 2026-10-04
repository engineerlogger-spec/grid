package com.grid.app.feature.bank

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.BuildConfig
import com.grid.app.core.bank.BankAuthInbox
import com.grid.app.core.bank.BankConnectorProvider
import com.grid.app.core.bank.BankConnectors
import com.grid.app.core.bank.BankCredentials
import com.grid.app.core.bank.BankError
import com.grid.app.core.bank.BankKeyStore
import com.grid.app.core.bank.BankSync
import com.grid.app.core.bank.PemKeys
import com.grid.app.core.bank.SyncResult
import com.grid.app.core.data.db.entities.BankAccountEntity
import com.grid.app.core.data.db.entities.BankConnectionEntity
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.BankRepository
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriods
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class BankPhase { KEY, CONNECT, WAITING, ACCOUNTS, CONNECTED }
enum class Backfill { PERIOD, MONTHS_3, MONTHS_12 }
enum class BankSetupError { BAD_KEY, NEED_APP_ID, KEY_REFUSED, NO_REVOLUT, NOT_GRANTED, NETWORK }

sealed interface BankEvent {
    data class OpenUrl(val url: String) : BankEvent
    data class Synced(val newItems: Int) : BankEvent
    data object RateLimited : BankEvent
    data object Expired : BankEvent
    data class Failed(val message: String) : BankEvent
}

data class BankSetupUi(
    val loading: Boolean = true,
    val phase: BankPhase = BankPhase.KEY,
    val appId: String? = null,
    val connection: BankConnectionEntity? = null,
    val accounts: List<BankAccountEntity> = emptyList(),
    val appCurrency: String = "EUR",
    val country: String = "FR",
    val busy: Boolean = false,
    val error: BankSetupError? = null,
)

@HiltViewModel
class BankSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bank: BankRepository,
    private val keyStore: BankKeyStore,
    private val connectors: BankConnectorProvider,
    private val inbox: BankAuthInbox,
    private val sync: BankSync,
    private val settings: SettingsRepository,
    private val clock: AppClock,
) : ViewModel() {

    private val appId = MutableStateFlow(keyStore.load()?.appId)
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<BankSetupError?>(null)
    private val choosingAccounts = MutableStateFlow(false)
    private val country = MutableStateFlow(defaultCountry())

    private val _events = MutableSharedFlow<BankEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<BankEvent> = _events

    private val local = combine(appId, busy, error, choosingAccounts, country) { id, b, e, choosing, c -> Local(id, b, e, choosing, c) }

    private data class Local(val appId: String?, val busy: Boolean, val error: BankSetupError?, val choosing: Boolean, val country: String)

    val state: StateFlow<BankSetupUi> = combine(bank.observeConnection(), bank.observeAccounts(), settings.settings, local) { connection, accounts, s, l ->
        val phase = when {
            l.appId == null -> BankPhase.KEY
            l.choosing -> BankPhase.ACCOUNTS
            connection?.sessionId != null -> BankPhase.CONNECTED
            connection?.authState != null -> BankPhase.WAITING
            else -> BankPhase.CONNECT
        }
        BankSetupUi(
            loading = false, phase = phase, appId = l.appId, connection = connection, accounts = accounts,
            appCurrency = s.currency, country = connection?.aspspCountry ?: l.country, busy = l.busy, error = l.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BankSetupUi())

    val isDebug: Boolean = BuildConfig.DEBUG

    init {
        // The bank's login came back (MainActivity posted it): finish the connection.
        viewModelScope.launch {
            inbox.pending.filterNotNull().collect { callback ->
                inbox.consume()
                complete(callback)
            }
        }
    }

    fun setCountry(code: String) {
        country.value = code
    }

    fun importKey(uri: Uri, appIdInput: String) = work {
        val pem = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        if (pem == null || runCatching { PemKeys.parsePrivateKey(pem) }.isFailure) {
            error.value = BankSetupError.BAD_KEY
            return@work
        }
        val id = appIdInput.trim().ifBlank { null } ?: UUID_PATTERN.find(displayName(uri).orEmpty())?.value
        if (id == null) {
            error.value = BankSetupError.NEED_APP_ID
            return@work
        }
        keyStore.save(BankCredentials(id, pem))
        // Prove the key works before going on: Enable Banking refuses unknown keys straight away.
        try {
            connectors.current()?.aspsps(country.value)
            appId.value = id
        } catch (e: BankError.Unauthorized) {
            keyStore.clear()
            error.value = BankSetupError.KEY_REFUSED
        } catch (e: BankError) {
            appId.value = id
            error.value = BankSetupError.NETWORK
        }
    }

    fun useDemo() {
        if (!BuildConfig.DEBUG) return
        keyStore.save(BankCredentials(BankConnectors.DEMO_APP_ID, ""))
        appId.value = BankConnectors.DEMO_APP_ID
    }

    fun changeKey() {
        keyStore.clear()
        appId.value = null
    }

    /** Opens the bank's consent page. [backfill] is null on a reconnect (keeps the original history start). */
    fun connect(backfill: Backfill?) = work {
        val connector = connectors.current() ?: return@work
        val banks = try {
            connector.aspsps(country.value)
        } catch (e: BankError) {
            error.value = if (e is BankError.Unauthorized) BankSetupError.KEY_REFUSED else BankSetupError.NETWORK
            return@work
        }
        val revolut = banks.firstOrNull { it.name.equals("Revolut", ignoreCase = true) } ?: banks.firstOrNull { it.name.contains("revolut", ignoreCase = true) }
        if (revolut == null) {
            error.value = BankSetupError.NO_REVOLUT
            return@work
        }
        val today = clock.today()
        val from = when (backfill) {
            Backfill.PERIOD -> BudgetPeriods.periodFor(today, settings.settings.first().periodStartDay).start
            Backfill.MONTHS_3 -> today.minusMonths(3)
            Backfill.MONTHS_12 -> today.minusMonths(12)
            null -> null
        }
        val state = UUID.randomUUID().toString()
        bank.beginAuth(revolut, state, from?.toEpochDay())
        val validUntil = clock.millis() + TimeUnit.SECONDS.toMillis(revolut.maxConsentSeconds ?: DEFAULT_CONSENT_SECONDS)
        try {
            _events.emit(BankEvent.OpenUrl(connector.startAuth(revolut, validUntil, BankSync.REDIRECT_URL, state).url))
        } catch (e: BankError) {
            error.value = BankSetupError.NETWORK
        }
    }

    /** The bounce page couldn't open the app: the user pasted its address. Returns false if it isn't one. */
    fun pasteLink(text: String): Boolean = inbox.post(text)

    private suspend fun complete(callback: BankAuthInbox.Callback) = work {
        val code = callback.code
        if (callback.error != null || code == null) {
            error.value = BankSetupError.NOT_GRANTED
            return@work
        }
        val connector = connectors.current() ?: return@work
        try {
            val session = connector.createSession(code)
            val result = bank.completeAuth(callback.state.orEmpty(), session, settings.settings.first().currency)
            if (result.firstConnect) choosingAccounts.value = true else runSync()
        } catch (e: IllegalStateException) {
            error.value = BankSetupError.NOT_GRANTED
        } catch (e: BankError) {
            error.value = BankSetupError.NETWORK
        }
    }.join()

    fun setEnabled(account: BankAccountEntity, enabled: Boolean) = viewModelScope.launch { bank.setEnabled(account.id, enabled) }

    fun startSync() = work {
        choosingAccounts.value = false
        runSync()
    }

    fun syncNow() = work { runSync() }

    private suspend fun runSync() {
        val event = when (val result = sync.run()) {
            is SyncResult.Ok -> BankEvent.Synced(result.toReview + result.booked)
            SyncResult.RateLimited -> BankEvent.RateLimited
            SyncResult.Expired -> BankEvent.Expired
            is SyncResult.Failed -> BankEvent.Failed(result.message)
            SyncResult.NotConnected -> null
        }
        event?.let { _events.emit(it) }
    }

    fun disconnect() = work {
        val connection = bank.connection()
        connection?.sessionId?.let { id -> runCatching { connectors.current()?.deleteSession(id) } }
        bank.disconnect()
        keyStore.clear()
        appId.value = null
    }

    suspend fun rawData(): String = bank.recentRaw()

    fun clearError() {
        error.value = null
    }

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        error.value = null
        try {
            block()
        } finally {
            busy.value = false
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun defaultCountry(): String = Locale.getDefault().country.takeIf { it in COUNTRIES } ?: "FR"

    companion object {
        /** Countries where Enable Banking offers Revolut (EEA + UK). */
        val COUNTRIES = listOf(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IS", "IE", "IT", "LV", "LI",
            "LT", "LU", "MT", "NL", "NO", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "GB",
        )
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val DEFAULT_CONSENT_SECONDS = TimeUnit.DAYS.toSeconds(180)
    }
}
