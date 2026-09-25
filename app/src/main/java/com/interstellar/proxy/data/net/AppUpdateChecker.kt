package com.interstellar.proxy.data.net

import com.interstellar.proxy.BuildConfig
import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * App self-update check against the GitHub release page.
 *
 * The latest tag is read from the `releases/latest` 302 redirect (satelite-
 * proxy's method): github.com redirects to `…/releases/tag/<tag>`, drawing on
 * the website's budget instead of api.github.com's 60 req/h unauthenticated
 * cap, which 403s easily behind shared NAT/proxy exits. Requests ride the
 * local mixed inbound when a core is running (GitHub is unreachable directly
 * in CN), then fall back to direct.
 */
object AppUpdateChecker {
    const val RELEASES_PAGE = "https://github.com/zn0wii/interstellar-proxy/releases/latest"
    private const val MIXED_PORT = 2080

    sealed interface Result {
        data class UpdateAvailable(val latestTag: String) : Result
        data class UpToDate(val currentTag: String) : Result
    }

    suspend fun check(): Result {
        val latest = normalize(fetchLatestTag())
        val current = normalize(BuildConfig.VERSION_NAME)
        return if (isNewer(latest, current)) Result.UpdateAvailable(latest) else Result.UpToDate(current)
    }

    /** `…/releases/tag/<tag>` (absolute or relative, with query) → tag. */
    internal fun extractTag(location: String): String? {
        val tag = location.substringAfter("/releases/tag/", "")
            .substringBefore('?')
            .trimEnd('/')
        return tag.takeIf { it.isNotBlank() }
    }

    /** "0.5.10" > "0.5.9" — numeric segment compare, not lexicographic. */
    internal fun isNewer(candidate: String, current: String): Boolean {
        val a = normalize(candidate).split('.').map { it.toIntOrNull() ?: 0 }
        val b = normalize(current).split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    internal fun normalize(version: String): String = version.trim().removePrefix("v").removePrefix("V")

    // no redirect follow: the 302 Location header carries the tag
    private val directClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val proxiedClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", MIXED_PORT)))
        .build()

    /** Same liveness split as SubscriptionFetcher. */
    private fun coreRunning(): Boolean = when (Settings.coreKind) {
        com.interstellar.proxy.core.CoreKind.SINGBOX ->
            File(InterstellarApplication.application.filesDir, "command.sock").exists()

        com.interstellar.proxy.core.CoreKind.MIHOMO ->
            com.interstellar.proxy.core.MihomoCore.Holder.instance != null

        com.interstellar.proxy.core.CoreKind.XRAY ->
            com.interstellar.proxy.core.XrayCore.Holder.instance != null
    }

    private suspend fun fetchLatestTag(): String = withContext(Dispatchers.IO) {
        val clients = buildList {
            if (coreRunning()) add(proxiedClient)
            add(directClient)
        }
        var lastError: Exception? = null
        for (client in clients) {
            try {
                val request = Request.Builder().url(RELEASES_PAGE).build()
                val tag = client.newCall(request).execute().use { response ->
                    if (!response.isRedirect) error("HTTP ${response.code}")
                    val location = response.header("Location")
                        ?: error(com.interstellar.proxy.ktx.AppLanguage.getString(InterstellarApplication.application, com.interstellar.proxy.R.string.update_no_location))
                    extractTag(location)
                        ?: error(com.interstellar.proxy.ktx.AppLanguage.getString(InterstellarApplication.application, com.interstellar.proxy.R.string.update_no_tag))
                }
                return@withContext tag
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException(
            com.interstellar.proxy.ktx.AppLanguage.getString(InterstellarApplication.application, com.interstellar.proxy.R.string.update_check_failed),
        )
    }
}
