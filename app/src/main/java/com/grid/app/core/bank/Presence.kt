package com.grid.app.core.bank

import android.os.Build
import com.grid.app.BuildConfig
import com.grid.app.core.time.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The person is in the app right now. Without it, banks answer only about four requests a day per account and per
 * kind of data (PSD2 background access); a request carrying the person's internet address and app is theirs and
 * isn't counted (Enable Banking's PSU headers).
 */
data class Presence(val ipAddress: String, val userAgent: String)

/** The phone's public internet address, as banks see it: asked to an address echo (ipify), kept a few minutes. */
@Singleton
class PresenceProvider(private val http: OkHttpClient, private val clock: AppClock, private val echoUrl: String) {
    @Inject constructor(http: OkHttpClient, clock: AppClock) : this(http, clock, ECHO_URL)

    @Volatile private var cached: Pair<Long, Presence>? = null

    /** Null when offline or the echo didn't answer an IPv4 address (banks expect one). */
    suspend fun now(): Presence? {
        cached?.takeIf { clock.millis() - it.first < CACHE_MS }?.let { return it.second }
        val ip = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(echoUrl).build()).execute().use { response ->
                    response.body.string().trim().takeIf { response.isSuccessful && IPV4.matches(it) }
                }
            }.getOrNull()
        } ?: return null
        return Presence(ip, "Grid/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})")
            .also { cached = clock.millis() to it }
    }

    private companion object {
        const val ECHO_URL = "https://api.ipify.org"
        val CACHE_MS = TimeUnit.MINUTES.toMillis(5)
        val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}
