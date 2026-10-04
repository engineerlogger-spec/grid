package com.grid.app.core.bank

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.TimeUnit

object ConsentWindow {
    private val DAY = TimeUnit.DAYS.toMillis(1)
    private val MINUTE = TimeUnit.MINUTES.toMillis(1)
    private const val FALLBACK_SECONDS = 90L * 24 * 3600

    /**
     * When the requested consent ends. Banks reject anything past their maximum (HTTP 422), so stay a day inside it:
     * a phone clock slightly ahead of the server would otherwise tip an exact-maximum request over the limit.
     */
    fun validUntil(now: Long, maxSeconds: Long?): Long {
        val max = TimeUnit.SECONDS.toMillis(maxSeconds ?: FALLBACK_SECONDS)
        val margin = if (max > 2 * DAY) DAY else MINUTE
        return now + max - margin
    }

    /** The human-readable "message" of an Enable Banking error body, if it has one. */
    fun serverMessage(body: String): String? = runCatching {
        (Json.parseToJsonElement(body).jsonObject["message"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
