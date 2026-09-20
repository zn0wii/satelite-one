package com.interstellar.proxy.data.net

import android.util.Log
import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.core.CoreKind
import com.interstellar.proxy.data.RulesStore
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
 * Updates the built-in rule / geodata files from their upstream sources:
 *
 *  - sing-box: SagerNet sing-geosite / sing-geoip binary rule sets (.srs)
 *  - mihomo:   MetaCubeX meta-rules-dat geosite.dat + geoip.metadb
 *  - Xray:     v2fly geosite (dlc.dat) + geoip.dat
 *
 * Downloads ride the local mixed inbound when a core is running (same escape
 * hatch as subscription updates — GitHub is unreachable directly in CN), then
 * fall back to direct. Each file is downloaded to a temp sibling, sanity
 * checked and swapped in atomically, so a failed update never leaves a
 * corrupt rule file behind.
 */
object GeoRuleUpdater {
    private const val TAG = "GeoRuleUpdater"
    private const val MIXED_PORT = 2080

    private data class GeoFile(
        val name: String,
        val urls: List<String>,
        val target: File,
        val validate: (File) -> Boolean,
    )

    private val directClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true) // github releases/latest → objects.githubusercontent.com
        .build()

    private val proxiedClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", MIXED_PORT)))
        .build()

    /** Same liveness split as SubscriptionFetcher. */
    private fun coreRunning(): Boolean = when (Settings.coreKind) {
        CoreKind.SINGBOX ->
            File(InterstellarApplication.application.filesDir, "command.sock").exists()

        CoreKind.MIHOMO -> com.interstellar.proxy.core.MihomoCore.Holder.instance != null
        CoreKind.XRAY -> com.interstellar.proxy.core.XrayCore.Holder.instance != null
    }

    /** The rule files the given core loads, with their download sources. */
    private fun filesFor(core: CoreKind): List<GeoFile> {
        val filesDir = InterstellarApplication.application.filesDir
        return when (core) {
            CoreKind.SINGBOX -> listOf(
                srsFile(
                    RulesStore.geolocationNotCn,
                    "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-geolocation-!cn.srs",
                ),
                srsFile(
                    RulesStore.geositeCn,
                    "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs",
                ),
                srsFile(
                    RulesStore.geoipCn,
                    "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs",
                ),
                srsFile(
                    RulesStore.adsAll,
                    "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/category-ads-all.srs",
                ),
            )

            CoreKind.MIHOMO -> listOf(
                geoFile(
                    "geosite.dat",
                    File(filesDir, "mihomo/geosite.dat"),
                    "https://github.com/MetaCubeX/meta-rules-dat/releases/latest/download/geosite.dat",
                ),
                geoFile(
                    "geoip.metadb",
                    File(filesDir, "mihomo/geoip.metadb"),
                    "https://github.com/MetaCubeX/meta-rules-dat/releases/latest/download/geoip.metadb",
                ),
            )

            CoreKind.XRAY -> listOf(
                geoFile(
                    "geosite.dat",
                    File(filesDir, "xray/geosite.dat"),
                    "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat",
                ),
                geoFile(
                    "geoip.dat",
                    File(filesDir, "xray/geoip.dat"),
                    "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat",
                ),
            )
        }
    }

    /** srs with a jsdelivr mirror (both repos expose a rule-set branch). */
    private fun srsFile(asset: RulesStore.RuleAsset, url: String): GeoFile = GeoFile(
        name = asset.fileName,
        urls = listOf(url, url.replace("https://raw.githubusercontent.com/", "https://cdn.jsdelivr.net/gh/")
            .replace("/rule-set/", "@rule-set/")),
        target = RulesStore.fileOf(asset),
        validate = ::srsValid,
    )

    private fun geoFile(name: String, target: File, url: String): GeoFile =
        GeoFile(name, listOf(url), target, ::geoValid)

    // ---- validation ----

    /** Valid srs starts with "SRS\x01" + zlib stream (same sanity as RulesStore). */
    private fun srsValid(f: File): Boolean = f.length() > 8 && startsWith(f, "SRS")

    /** Real .dat/.metadb are multi-MB protobufs; a captive-portal/error page is small or HTML. */
    private fun geoValid(f: File): Boolean = f.length() > 1_000_000 && !startsWith(f, "<")

    private fun startsWith(f: File, prefix: String): Boolean = runCatching {
        f.inputStream().use { input ->
            val buf = ByteArray(prefix.length)
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) return@use false
                off += n
            }
            buf.decodeToString() == prefix
        }
    }.getOrDefault(false)

    // ---- update ----

    /**
     * Downloads every rule file of [core]. Returns a user-facing summary;
     * throws when nothing could be updated.
     */
    suspend fun update(core: CoreKind): String = withContext(Dispatchers.IO) {
        val throughProxy = coreRunning()
        var ok = 0
        val failed = mutableListOf<String>()
        for (file in filesFor(core)) {
            val attempt = runCatching { download(file, throughProxy) }
            if (attempt.isSuccess) {
                ok++
            } else {
                failed += file.name
                Log.w(TAG, "update ${file.name} failed: ${attempt.exceptionOrNull()?.message}")
            }
        }
        if (ok == 0) {
            error("下载失败(${failed.joinToString("、")}),请检查网络后重试")
        }
        Settings.ruleFilesUpdatedAt = System.currentTimeMillis()
        // keep the sidecar extraction markers truthful so a later startup's
        // ensureGeodata() never re-overwrites the fresh files with bundled ones
        if (core == CoreKind.MIHOMO || core == CoreKind.XRAY) {
            val dir = if (core == CoreKind.MIHOMO) "mihomo" else "xray"
            File(File(InterstellarApplication.application.filesDir, dir), "geodata.extracted").writeText("1")
        }
        if (failed.isEmpty()) "规则文件已更新($ok 个)" else "已更新 $ok 个,失败:${failed.joinToString("、")}"
    }

    /** One file: temp download → validate → atomic swap. */
    private fun download(file: GeoFile, throughProxy: Boolean) {
        file.target.parentFile?.mkdirs()
        val tmp = File(file.target.parentFile, file.target.name + ".tmp")
        // proxied first when a core runs (the node reaches GitHub), then direct
        val clients = buildList {
            if (throughProxy) add(proxiedClient)
            add(directClient)
        }
        var lastError: Exception? = null
        var downloaded = false
        try {
            outer@ for (url in file.urls) {
                for (client in clients) {
                    try {
                        val request = Request.Builder().url(url).build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) error("HTTP ${response.code}")
                            response.body!!.byteStream().use { input ->
                                tmp.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        downloaded = true
                        break@outer
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
            }
            if (!downloaded) throw lastError ?: IllegalStateException("下载失败")
            if (!file.validate(tmp)) {
                throw IllegalStateException("文件校验失败(非有效规则文件)")
            }
            if (!tmp.renameTo(file.target)) {
                // rename can fail across a mounted state or AV scan — replace instead
                file.target.delete()
                if (!tmp.renameTo(file.target)) throw IllegalStateException("替换文件失败")
            }
        } finally {
            tmp.delete()
        }
    }
}
