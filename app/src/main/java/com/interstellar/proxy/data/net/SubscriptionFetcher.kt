package com.interstellar.proxy.data.net

import com.interstellar.proxy.InterstellarApplication
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Downloads subscription content, ported from interstellar-proxy's services/import.rs:
 * UA masquerades as clash-verge so panels return the subscription-userinfo header.
 *
 * When the core is running, requests go through the local mixed inbound
 * (127.0.0.1:2080) so they ride the selected node; falls back to direct.
 */
object SubscriptionFetcher {
    private const val MIXED_PORT = 2080

    private fun directClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun proxiedClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", MIXED_PORT)))
        .build()

    /**
     * Per-core liveness (same split as NetProbe): sing-box owns the command
     * socket, sidecar cores expose their Holder handles.
     */
    private fun coreRunning(): Boolean = when (com.interstellar.proxy.data.Settings.coreKind) {
        com.interstellar.proxy.core.CoreKind.SINGBOX ->
            File(InterstellarApplication.application.filesDir, "command.sock").exists()

        com.interstellar.proxy.core.CoreKind.MIHOMO ->
            com.interstellar.proxy.core.MihomoCore.Holder.instance != null

        com.interstellar.proxy.core.CoreKind.XRAY ->
            com.interstellar.proxy.core.XrayCore.Holder.instance != null
    }

    data class FetchResult(
        val body: String,
        val uploadBytes: Long = 0,
        val downloadBytes: Long = 0,
        val totalBytes: Long = 0,
        val expireSeconds: Long = 0,
        val suggestedName: String? = null,
        val viaProxy: Boolean = false,
    )

    suspend fun fetch(url: String): FetchResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        val throughProxy = coreRunning()
        if (throughProxy) {
            try {
                return executeCancellable(proxiedClient(), request, viaProxy = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // proxy path failed (node down / core stopping) — retry direct
            }
        }
        return executeCancellable(directClient(), request, viaProxy = false)
    }

    /** Enqueue + invokeOnCancellation so the dialog's 取消 aborts the socket. */
    private suspend fun executeCancellable(
        client: OkHttpClient,
        request: Request,
        viaProxy: Boolean,
    ): FetchResult = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (cont.isActive) cont.resumeWith(kotlin.Result.failure(e))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val parsed = runCatching {
                    response.use {
                        if (!it.isSuccessful) error("HTTP ${it.code}")
                        val body = it.body!!.string()
                        val info = it.header("subscription-userinfo")
                            ?.let { h -> parseSubscriptionUserinfo(h) }
                        val name = parseDispositionName(it.header("Content-Disposition"))
                        FetchResult(
                            body = body,
                            uploadBytes = info?.get("upload") ?: 0,
                            downloadBytes = info?.get("download") ?: 0,
                            totalBytes = info?.get("total") ?: 0,
                            expireSeconds = info?.get("expire") ?: 0,
                            suggestedName = name,
                            viaProxy = viaProxy,
                        )
                    }
                }
                if (cont.isActive) cont.resumeWith(parsed)
            }
        })
    }

    // upload=123; download=456; total=789; expire=1750000000
    private fun parseSubscriptionUserinfo(header: String): Map<String, Long> {
        return header.split(';')
            .mapNotNull { part ->
                val key = part.substringBefore('=').trim().lowercase()
                val value = part.substringAfter('=', "").trim().toLongOrNull() ?: return@mapNotNull null
                key to value
            }.toMap()
    }

    /**
     * Content-Disposition display name, FlClash-compatible (satelite-proxy's
     * parser): prefer RFC 5987 `filename*=UTF-8''%E8%89%AF…` — what CN panels
     * actually send — then plain `filename=` (some servers percent-encode it
     * too). A bare `filename=` regex misses the star form entirely and the
     * subscription falls back to its URL host.
     */
    fun parseDispositionName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        findDispositionParam(header, "filename*")?.let { star ->
            decodeFilenameStar(star)
                ?.let(::cleanDispositionName)
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        findDispositionParam(header, "filename")?.let { plain ->
            val unquoted = plain.trim().trim('"', '\'')
            percentDecode(unquoted)
                ?.let(::cleanDispositionName)
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return null
    }

    private fun findDispositionParam(disposition: String, key: String): String? =
        disposition.split(';')
            .map { it.trim() }
            .firstNotNullOfOrNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@firstNotNullOfOrNull null
                if (part.substring(0, idx).trim().equals(key, ignoreCase = true)) {
                    part.substring(idx + 1).trim()
                } else {
                    null
                }
            }

    /** `UTF-8''%E8%89%AF…` (charset'lang'value) → decoded value. */
    private fun decodeFilenameStar(raw: String): String? {
        val trimmed = raw.trim().trim('"', '\'')
        val value = when {
            "''" in trimmed -> trimmed.substringAfter("''")
            "'" in trimmed -> trimmed.substringAfter('\'')
            else -> trimmed
        }.trim()
        if (value.isEmpty()) return null
        return percentDecode(value)?.takeIf { it.isNotEmpty() }
    }

    /** URLDecoder also maps '+' to space — shield literal pluses first. */
    private fun percentDecode(value: String): String? = runCatching {
        java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value.takeIf { it.isNotBlank() })

    /** Drop path components and common subscription file extensions. */
    private fun cleanDispositionName(name: String): String {
        var s = name.trim().substringAfterLast('/').substringAfterLast('\\')
        for (ext in listOf(".yaml", ".yml", ".txt", ".conf", ".json")) {
            if (s.lowercase().endsWith(ext)) {
                s = s.dropLast(ext.length)
                break
            }
        }
        return s.trim()
    }

    private const val USER_AGENT = "Interstellar/0.5 clash-verge/v2.5 flclash/1 Android"
}
