package com.grid.app.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.grid.app.core.model.CaptureSource
import com.grid.app.core.model.ThemeMode
import com.grid.app.core.money.Currencies
import com.grid.app.core.time.BudgetPeriods
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

data class AppSettings(
    val currency: String,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    /** Day of month a budget period starts (1–28), e.g. 25 for people paid on the 25th. */
    val periodStartDay: Int = 1,
    val onboardingDone: Boolean = false,
    val hideAmounts: Boolean = false,
    val appLock: Boolean = false,
    val capture: CaptureSettings = CaptureSettings(),
    val backup: BackupSettings = BackupSettings(),
    val lowFunds: LowFundsMode = LowFundsMode.BALANCE,
    val ai: AiStatus = AiStatus(),
)

/** The last Gemini pass over payees and bills (the key itself lives encrypted in AiKeyStore). */
data class AiStatus(
    val lastRunAt: Long? = null,
    val lastError: String? = null,
    /** Payees recognised so far, and bills found in the last pass. */
    val payees: Int = 0,
    val bills: Int = 0,
)

/** What the low-funds warning compares upcoming bills against. */
enum class LowFundsMode {
    /** The Revolut balance read at each sync. */
    BALANCE,
    /** The balance minus the usual daily spending until the end of the month: warns earlier. */
    BALANCE_AND_SPENDING,
    /** What is left of the monthly budget (no bank needed). */
    BUDGET,
    OFF,
}

/** Payment detection preferences. All sources on by default; nothing happens until access is granted. */
data class CaptureSettings(
    val wallet: Boolean = true,
    val paypal: Boolean = true,
    val revolut: Boolean = true,
    /** Add payments from known merchants straight away (with an Undo notification). */
    val autoAdd: Boolean = true,
    /** Keep unrecognised notifications from these apps (locally, 30 days) to improve parsing. */
    val diagnostics: Boolean = true,
) {
    fun enabled(source: CaptureSource): Boolean = when (source) {
        CaptureSource.GOOGLE_WALLET -> wallet
        CaptureSource.PAYPAL -> paypal
        CaptureSource.REVOLUT -> revolut
    }
}

enum class BackupFrequency { OFF, DAILY, WEEKLY, MONTHLY }

/** Google Drive backup state (WhatsApp-style: periodic upload to the hidden app folder). */
data class BackupSettings(
    val frequency: BackupFrequency = BackupFrequency.WEEKLY,
    val wifiOnly: Boolean = true,
    /** The user connected a Google account at least once (auto-backup only runs when true). */
    val connected: Boolean = false,
    val account: String? = null,
    val lastAt: Long? = null,
    val lastSizeBytes: Long? = null,
    val lastError: String? = null,
)

/** Portable subset of settings stored inside backups (no device-specific state). */
@Serializable
data class SettingsSnapshot(
    val currency: String,
    val themeMode: String,
    val dynamicColor: Boolean,
    val periodStartDay: Int,
    val hideAmounts: Boolean,
    val appLock: Boolean,
    val captureWallet: Boolean,
    val capturePaypal: Boolean,
    val captureRevolut: Boolean,
    val captureAutoAdd: Boolean,
    val captureDiagnostics: Boolean,
    val backupFrequency: String,
    val backupWifiOnly: Boolean,
    val lowFunds: String = "BALANCE",
)

/** User preferences in DataStore. Ledger data lives in Room; this is configuration only. */
class SettingsRepository(
    private val store: DataStore<Preferences>,
    private val locale: Locale = Locale.getDefault(),
) {
    private object Keys {
        val currency = stringPreferencesKey("currency")
        val theme = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val periodStartDay = intPreferencesKey("period_start_day")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val hideAmounts = booleanPreferencesKey("hide_amounts")
        val appLock = booleanPreferencesKey("app_lock")
        val captureWallet = booleanPreferencesKey("capture_wallet")
        val capturePaypal = booleanPreferencesKey("capture_paypal")
        val captureRevolut = booleanPreferencesKey("capture_revolut")
        val captureAutoAdd = booleanPreferencesKey("capture_auto_add")
        val captureDiagnostics = booleanPreferencesKey("capture_diagnostics")
        val backupFrequency = stringPreferencesKey("backup_frequency")
        val backupWifiOnly = booleanPreferencesKey("backup_wifi_only")
        val backupConnected = booleanPreferencesKey("backup_connected")
        val backupAccount = stringPreferencesKey("backup_account")
        val backupLastAt = longPreferencesKey("backup_last_at")
        val backupLastSize = longPreferencesKey("backup_last_size")
        val backupLastError = stringPreferencesKey("backup_last_error")
        val lowFunds = stringPreferencesKey("low_funds")
        val aiLastRunAt = longPreferencesKey("ai_last_run_at")
        val aiLastError = stringPreferencesKey("ai_last_error")
        val aiPayees = intPreferencesKey("ai_payees")
        val aiBills = intPreferencesKey("ai_bills")
        val aiDigestDay = longPreferencesKey("ai_digest_day")
        val aiDigest = stringPreferencesKey("ai_digest")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            currency = p[Keys.currency] ?: Currencies.defaultFor(locale),
            themeMode = p[Keys.theme]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.dynamicColor] ?: false,
            periodStartDay = p[Keys.periodStartDay] ?: 1,
            onboardingDone = p[Keys.onboardingDone] ?: false,
            hideAmounts = p[Keys.hideAmounts] ?: false,
            appLock = p[Keys.appLock] ?: false,
            capture = CaptureSettings(
                wallet = p[Keys.captureWallet] ?: true,
                paypal = p[Keys.capturePaypal] ?: true,
                revolut = p[Keys.captureRevolut] ?: true,
                autoAdd = p[Keys.captureAutoAdd] ?: true,
                diagnostics = p[Keys.captureDiagnostics] ?: true,
            ),
            backup = BackupSettings(
                frequency = p[Keys.backupFrequency]?.let { n -> BackupFrequency.entries.firstOrNull { it.name == n } } ?: BackupFrequency.WEEKLY,
                wifiOnly = p[Keys.backupWifiOnly] ?: true,
                connected = p[Keys.backupConnected] ?: false,
                account = p[Keys.backupAccount],
                lastAt = p[Keys.backupLastAt],
                lastSizeBytes = p[Keys.backupLastSize],
                lastError = p[Keys.backupLastError],
            ),
            lowFunds = p[Keys.lowFunds]?.let { n -> LowFundsMode.entries.firstOrNull { it.name == n } } ?: LowFundsMode.BALANCE,
            ai = AiStatus(p[Keys.aiLastRunAt], p[Keys.aiLastError], p[Keys.aiPayees] ?: 0, p[Keys.aiBills] ?: 0),
        )
    }.distinctUntilChanged()

    suspend fun setCaptureSource(source: CaptureSource, enabled: Boolean) = store.edit {
        it[
            when (source) {
                CaptureSource.GOOGLE_WALLET -> Keys.captureWallet
                CaptureSource.PAYPAL -> Keys.capturePaypal
                CaptureSource.REVOLUT -> Keys.captureRevolut
            },
        ] = enabled
    }
    suspend fun setCaptureAutoAdd(enabled: Boolean) = store.edit { it[Keys.captureAutoAdd] = enabled }
    suspend fun setCaptureDiagnostics(enabled: Boolean) = store.edit { it[Keys.captureDiagnostics] = enabled }

    suspend fun setCurrency(code: String) = store.edit { it[Keys.currency] = code }
    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = store.edit { it[Keys.dynamicColor] = enabled }
    suspend fun setPeriodStartDay(day: Int) = store.edit {
        it[Keys.periodStartDay] = day.coerceIn(BudgetPeriods.MIN_START_DAY, BudgetPeriods.MAX_START_DAY)
    }
    suspend fun completeOnboarding() = store.edit { it[Keys.onboardingDone] = true }
    suspend fun setHideAmounts(hidden: Boolean) = store.edit { it[Keys.hideAmounts] = hidden }
    suspend fun setAppLock(enabled: Boolean) = store.edit { it[Keys.appLock] = enabled }
    suspend fun setLowFunds(mode: LowFundsMode) = store.edit { it[Keys.lowFunds] = mode.name }
    suspend fun recordAiRun(at: Long, payees: Int, bills: Int) = store.edit {
        it[Keys.aiLastRunAt] = at
        it[Keys.aiPayees] = payees
        it[Keys.aiBills] = bills
        it.remove(Keys.aiLastError)
    }
    suspend fun recordAiError(message: String) = store.edit { it[Keys.aiLastError] = message }

    /** Gemini's monthly notes as written on [epochDay] (JSON). */
    suspend fun digest(): Pair<Long, String>? = store.data.first().let { p -> p[Keys.aiDigestDay]?.let { day -> p[Keys.aiDigest]?.let { day to it } } }
    suspend fun saveDigest(epochDay: Long, json: String) = store.edit {
        it[Keys.aiDigestDay] = epochDay
        it[Keys.aiDigest] = json
    }

    suspend fun setBackupFrequency(frequency: BackupFrequency) = store.edit { it[Keys.backupFrequency] = frequency.name }
    suspend fun setBackupWifiOnly(wifiOnly: Boolean) = store.edit { it[Keys.backupWifiOnly] = wifiOnly }
    suspend fun setBackupConnected(account: String?) = store.edit {
        it[Keys.backupConnected] = true
        if (account != null) it[Keys.backupAccount] = account else it.remove(Keys.backupAccount)
    }
    suspend fun disconnectBackup() = store.edit { it[Keys.backupConnected] = false; it.remove(Keys.backupAccount) }
    suspend fun recordBackup(at: Long, sizeBytes: Long) = store.edit {
        it[Keys.backupLastAt] = at
        it[Keys.backupLastSize] = sizeBytes
        it.remove(Keys.backupLastError)
    }
    suspend fun recordBackupError(message: String) = store.edit { it[Keys.backupLastError] = message }

    /** Settings that travel inside a backup file. */
    suspend fun exportJson(): String {
        val s = settings.first()
        return json.encodeToString(
            SettingsSnapshot.serializer(),
            SettingsSnapshot(
                currency = s.currency, themeMode = s.themeMode.name, dynamicColor = s.dynamicColor, periodStartDay = s.periodStartDay,
                hideAmounts = s.hideAmounts, appLock = s.appLock,
                captureWallet = s.capture.wallet, capturePaypal = s.capture.paypal, captureRevolut = s.capture.revolut,
                captureAutoAdd = s.capture.autoAdd, captureDiagnostics = s.capture.diagnostics,
                backupFrequency = s.backup.frequency.name, backupWifiOnly = s.backup.wifiOnly, lowFunds = s.lowFunds.name,
            ),
        )
    }

    /** Applies settings from a backup. A restored install is onboarded by definition. */
    suspend fun importJson(raw: String) {
        val snapshot = runCatching { json.decodeFromString(SettingsSnapshot.serializer(), raw) }.getOrNull()
        store.edit { p ->
            p[Keys.onboardingDone] = true
            snapshot ?: return@edit
            p[Keys.currency] = snapshot.currency
            p[Keys.theme] = snapshot.themeMode
            p[Keys.dynamicColor] = snapshot.dynamicColor
            p[Keys.periodStartDay] = snapshot.periodStartDay.coerceIn(BudgetPeriods.MIN_START_DAY, BudgetPeriods.MAX_START_DAY)
            p[Keys.hideAmounts] = snapshot.hideAmounts
            p[Keys.appLock] = snapshot.appLock
            p[Keys.captureWallet] = snapshot.captureWallet
            p[Keys.capturePaypal] = snapshot.capturePaypal
            p[Keys.captureRevolut] = snapshot.captureRevolut
            p[Keys.captureAutoAdd] = snapshot.captureAutoAdd
            p[Keys.captureDiagnostics] = snapshot.captureDiagnostics
            p[Keys.backupFrequency] = snapshot.backupFrequency
            p[Keys.backupWifiOnly] = snapshot.backupWifiOnly
            p[Keys.lowFunds] = snapshot.lowFunds
        }
    }
}
