package com.grid.app.core.bank

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URI
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the bank's redirect lands: `grid://bank-callback?code=…&state=…` opened by MainActivity, or a link the
 * user pasted when the browser couldn't hand control back. The bank setup screen consumes it.
 */
@Singleton
class BankAuthInbox @Inject constructor() {
    data class Callback(val code: String?, val state: String?, val error: String?)

    private val _pending = MutableStateFlow<Callback?>(null)
    val pending: StateFlow<Callback?> = _pending

    /** Returns false when [link] isn't a bank callback. */
    fun post(link: String): Boolean {
        val callback = parse(link) ?: return false
        _pending.value = callback
        return true
    }

    fun consume() {
        _pending.value = null
    }

    companion object {
        fun isCallback(scheme: String?, host: String?) = scheme == "grid" && host == "bank-callback"

        /** Accepts the app link and the https bounce page address, with or without extra text around it. */
        fun parse(link: String): Callback? {
            val text = link.trim()
            val uri = runCatching { URI(text.substringBefore(' ')) }.getOrNull() ?: return null
            val isApp = uri.scheme == "grid" && uri.host == "bank-callback"
            val isPage = uri.scheme == "https" && (uri.path ?: "").trimEnd('/').endsWith("bank-callback")
            if (!isApp && !isPage) return null
            val query = (uri.rawQuery ?: return null).split('&').mapNotNull { part ->
                val key = part.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                key to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }.toMap()
            val callback = Callback(query["code"], query["state"], query["error"] ?: query["error_description"])
            return callback.takeIf { it.code != null || it.error != null }
        }
    }
}
