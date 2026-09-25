package com.interstellar.proxy.data.net

import com.interstellar.proxy.InterstellarApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Exit-IP network probe, ported from satelite-proxy's exit_ip service:
 * races several public IP APIs and takes the first success. When the
 * core is running the requests ride the local mixed inbound, so the
 * result reflects the exit of the currently selected node.
 */
object NetProbe {

    data class Result(
        val ip: String,
        /** ISO country CODE (localized for display via LocalizedNames). */
        val country: String? = null,
        val latencyMs: Long,
        val viaProxy: Boolean,
    )

    private const val MIXED_PORT = 2080
    private const val TIMEOUT_SECONDS = 8L

    private data class Endpoint(
        val url: String,
        val parse: (String) -> Pair<String?, String?>,
    )

    private val endpoints = listOf(
        Endpoint("https://api.ip.sb/geoip") { body ->
            val ip = regexGroup(body, """"query"\s*:\s*"([^"]+)"""")
                ?: regexGroup(body, """"ip"\s*:\s*"([^"]+)"""")
            val cc = regexGroup(body, """"country_code"\s*:\s*"([^"]+)"""")
                ?: regexGroup(body, """"country"\s*:\s*"([^"]+)"""")
            ip to cc?.uppercase()
        },
        Endpoint("http://ip-api.com/json?fields=status(query,countryCode)") { body ->
            val ip = regexGroup(body, """"query"\s*:\s*"([^"]+)"""")
            val cc = regexGroup(body, """"countryCode"\s*:\s*"([^"]+)"""")
            ip to cc?.uppercase()
        },
        Endpoint("https://api.ipify.org?format=json") { body ->
            (regexGroup(body, """"ip"\s*:\s*"([^"]+)"""") ?: body.trim()) to null
        },
        Endpoint("https://ifconfig.me/ip") { body ->
            body.trim().takeIf { it.length <= 45 && it.isNotEmpty() } to null
        },
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val proxiedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", MIXED_PORT)))
            .build()
    }

    /** Xray's local inbound is socks-only (no mixed http). */
    private val proxiedSocksClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", MIXED_PORT)))
            .build()
    }

    private fun proxiedClient(): OkHttpClient =
        if (com.interstellar.proxy.data.Settings.coreKind == com.interstellar.proxy.core.CoreKind.XRAY) {
            proxiedSocksClient
        } else {
            proxiedHttpClient
        }

    /**
     * sing-box's command socket exists exactly while it runs; the sidecar
     * cores expose their Holder handles instead.
     */
    private fun coreRunning(): Boolean = when (com.interstellar.proxy.data.Settings.coreKind) {
        com.interstellar.proxy.core.CoreKind.SINGBOX ->
            File(InterstellarApplication.application.filesDir, "command.sock").exists()

        com.interstellar.proxy.core.CoreKind.MIHOMO ->
            com.interstellar.proxy.core.MihomoCore.Holder.instance != null

        com.interstellar.proxy.core.CoreKind.XRAY ->
            com.interstellar.proxy.core.XrayCore.Holder.instance != null
    }

    /**
     * Race all endpoints on the given path; first valid answer wins.
     * Falls back to direct when the proxy path fails entirely.
     */
    suspend fun probe(): Result = withContext(Dispatchers.IO) {
        if (coreRunning()) {
            runCatching { race(viaProxy = true) }.getOrNull()?.let { return@withContext it }
        }
        race(viaProxy = false)
    }

    private suspend fun race(viaProxy: Boolean): Result = coroutineScope {
        val startedAt = System.currentTimeMillis()
        val winner = endpoints.map { endpoint ->
            async {
                runCatching {
                    val request = Request.Builder()
                        .url(endpoint.url)
                        .header("User-Agent", "curl/8.9.1")
                        .build()
                    (if (viaProxy) proxiedClient() else client).newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        val body = response.body!!.string()
                        val (ip, country) = endpoint.parse(body)
                        if (ip.isNullOrBlank() || ('.' !in ip && ':' !in ip)) error("unrecognized body")
                        Result(ip, country, System.currentTimeMillis() - startedAt, viaProxy)
                    }
                }.getOrNull()
            }
        }.map { it.await() }.firstOrNull { it != null }
        winner ?: error(
            com.interstellar.proxy.ktx.AppLanguage.getString(InterstellarApplication.application, com.interstellar.proxy.R.string.probe_all_failed),
        )
    }

    private fun regexGroup(body: String, pattern: String): String? =
        Regex(pattern).find(body)?.groupValues?.get(1)
}
