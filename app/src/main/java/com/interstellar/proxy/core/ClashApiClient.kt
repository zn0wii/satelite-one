package com.interstellar.proxy.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Clash-compatible REST client — the mihomo control surface (the same API
 * sing-box's experimental clash_api exposes). Semantics follow
 * satelite-proxy's api/clash_api.rs.
 */
class ClashApiClient(
    private val port: Int,
    private val secret: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        // the system HTTP proxy (or a leftover VPN one) must never intercept
        // the loopback control API — raw TCP then works but HTTP "fails"
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // PUT /configs applies the whole config synchronously — an airport raw
    // config (thousands of rules / hundreds of proxies) can blow past the
    // default 10s read timeout mid-request and read as a failed reload
    private val reloadClient = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val base = "http://127.0.0.1:$port"
    private val auth get() = "Bearer $secret"

    suspend fun version(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = call(Request.Builder().url("$base/version").header("Authorization", auth).build())
            json.parseToJsonElement(body).jsonObject["version"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    /** Diagnostic variant — surfaces the real transport/HTTP error. */
    suspend fun versionOrThrow(): String = withContext(Dispatchers.IO) {
        val body = call(Request.Builder().url("$base/version").header("Authorization", auth).build())
        json.parseToJsonElement(body).jsonObject["version"]?.jsonPrimitive?.content ?: "unknown"
    }

    /** Full proxy map: name → { type, now, all, history } (GET /proxies). */
    suspend fun proxies(): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            json.parseToJsonElement(
                call(Request.Builder().url("$base/proxies").header("Authorization", auth).build()),
            ).jsonObject["proxies"]?.jsonObject
        }.getOrNull()
    }

    /** Hot-switch a select group (PUT /proxies/{group}). */
    suspend fun select(group: String, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject { put("name", name) }
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$base/proxies/${encode(group)}")
                .header("Authorization", auth)
                .put(payload)
                .build()
            call(request)
            true
        }.getOrDefault(false)
    }

    /**
     * Test every member of a group (GET /group/{group}/delay) — mihomo runs
     * the batch server-side and returns name → delay(ms).
     */
    suspend fun groupDelay(group: String, timeoutMs: Int = 5000): Map<String, Int>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = call(
                    Request.Builder()
                        .url("$base/group/${encode(group)}/delay?url=$TEST_URL&timeout=$timeoutMs")
                        .header("Authorization", auth)
                        .build(),
                )
                val result = mutableMapOf<String, Int>()
                for ((name, v) in json.parseToJsonElement(body).jsonObject) {
                    val delay = v.jsonPrimitive.content.toIntOrNull() ?: continue
                    result[name] = if (delay <= 0) -1 else delay
                }
                result
            }.getOrNull()
        }

    /** Test a single proxy (GET /proxies/{name}/delay). */
    suspend fun proxyDelay(name: String, timeoutMs: Int = 5000): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val body = call(
                Request.Builder()
                    .url("$base/proxies/${encode(name)}/delay?url=$TEST_URL&timeout=$timeoutMs")
                    .header("Authorization", auth)
                    .build(),
            )
            json.parseToJsonElement(body).jsonObject["delay"]?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull()
    }

    /** Connections snapshot + traffic totals (GET /connections). */
    suspend fun connections(): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            json.parseToJsonElement(
                call(Request.Builder().url("$base/connections").header("Authorization", auth).build()),
            ).jsonObject
        }.getOrNull()
    }

    /** Close one connection (DELETE /connections/{id}). */
    suspend fun deleteConnection(id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            call(
                Request.Builder()
                    .url("$base/connections/${encode(id)}")
                    .header("Authorization", auth)
                    .delete()
                    .build(),
            )
            true
        }.getOrDefault(false)
    }

    /** Close all connections (DELETE /connections). */
    suspend fun closeAllConnections(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            call(
                Request.Builder()
                    .url("$base/connections")
                    .header("Authorization", auth)
                    .delete()
                    .build(),
            )
            true
        }.getOrDefault(false)
    }

    /** Hot-reload the config file (PUT /configs). */
    suspend fun reload(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject { put("path", path) }
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$base/configs?force=true")
                .header("Authorization", auth)
                .put(payload)
                .build()
            call(request, reloadClient)
            true
        }.getOrDefault(false)
    }

    private fun call(request: Request, httpClient: OkHttpClient = client): String {
        httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(200)}")
            return body
        }
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        const val TEST_URL = "https://www.gstatic.com/generate_204"
    }
}
