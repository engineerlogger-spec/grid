package com.grid.app.core.bank

import com.grid.app.BuildConfig
import com.grid.app.core.time.AppClock
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Hands out the connector for the stored credentials, or null when bank sync isn't set up. */
fun interface BankConnectorProvider {
    fun current(): BankConnector?
}

@Singleton
class BankConnectors @Inject constructor(
    private val keyStore: BankKeyStore,
    private val http: OkHttpClient,
    private val clock: AppClock,
) : BankConnectorProvider {
    @Volatile private var cached: Pair<BankCredentials, BankConnector>? = null

    override fun current(): BankConnector? {
        val creds = keyStore.load() ?: return null
        cached?.takeIf { it.first == creds }?.let { return it.second }
        val connector = if (BuildConfig.DEBUG && creds.appId == DEMO_APP_ID) DemoBankConnector(clock) else EnableBankingClient(http, { creds })
        cached = creds to connector
        return connector
    }

    companion object {
        /** Debug builds only: credentials with this app id select the demo bank. */
        const val DEMO_APP_ID = "demo"
    }
}
