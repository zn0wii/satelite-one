package com.interstellar.proxy.core

import android.content.Context
import android.util.Log
import com.interstellar.proxy.core.AppLog
import com.interstellar.proxy.data.SubscriptionRepository
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.data.config.ConfigBuilder
import com.interstellar.proxy.data.config.MihomoConfigBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * mihomo (Clash Meta) sidecar engine. Runs the official android binary from
 * nativeLibraryDir with `-d <home> -f config.yaml`; control (hot node
 * switch, delay tests, config reload) goes through the Clash REST API.
 *
 * VPN mode: the config embeds tun.file-descriptor (auto-route false) — the
 * app owns the VpnService routes and excludes node server IPs to prevent
 * loops (Mishka-proven pattern).
 */
class MihomoCore(
    private val context: Context,
    private val host: CoreHost,
) : ProxyCore {
    override val kind = CoreKind.MIHOMO

    private val workDir = File(context.filesDir, "mihomo").apply { mkdirs() }
    private val configFile = File(workDir, "config.yaml")
    val api = ClashApiClient(API_PORT, Settings.apiSecret)

    private var sidecar: SidecarProcess? = null
    private var lastOverrides: CoreOverrides? = null

    /** fd of the tun handed over at spawn; re-injected on hot reloads. */
    private var activeTunFd: Int? = null

    private val scope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private var trafficJob: Job? = null

    override suspend fun startup() {
        ensureGeodata()
    }

    /**
     * mihomo fatals when GEOSITE/GEOIP rules can't resolve their databases,
     * and its built-in download needs a working network (chicken-and-egg on
     * a fresh start) — ship the databases in assets and extract once.
     */
    private fun ensureGeodata() {
        val marker = File(workDir, "geodata.extracted")
        val geosite = File(workDir, "geosite.dat")
        val geoip = File(workDir, "geoip.metadb")
        if (marker.isFile && geosite.isFile && geoip.isFile) return
        context.assets.open("geodata/geosite.dat").use { input ->
            geosite.outputStream().use { input.copyTo(it) }
        }
        context.assets.open("geodata/geoip.metadb").use { input ->
            geoip.outputStream().use { input.copyTo(it) }
        }
        marker.writeText("1")
        Log.i(TAG, "geodata extracted to $workDir")
    }

    override suspend fun applyConfig(config: String, overrides: CoreOverrides) {
        // per-app lives in the VPN builder, NOT in the yaml — a hot reload
        // can't apply it; detect the change and fall through to a full
        // respawn (which re-establishes the tun with the new app list)
        val perAppChanged = lastOverrides?.let {
            it.perAppEnabled != overrides.perAppEnabled ||
                it.perAppInclude != overrides.perAppInclude ||
                it.perAppPackages != overrides.perAppPackages
        } ?: false
        lastOverrides = overrides
        if (sidecar?.running == true && !perAppChanged) {
            // hot reload: rewrite the file, then ask mihomo to re-read it
            configFile.writeText(stripTun(config))
            if (api.reload(configFile.absolutePath)) {
                // a 2xx reload can still be followed by a fatal while the new
                // config applies — verify liveness before declaring success
                val survived = runCatching {
                    kotlinx.coroutines.delay(1500)
                    sidecar?.running == true && api.version() != null
                }.getOrDefault(false)
                if (survived) {
                    AppLog.log("mihomo", "配置已热重载")
                    applySelection(overrides)
                    return
                }
                AppLog.log("mihomo", "热重载后内核无响应,转为完整重启")
            }
            // API unreachable → process died between checks; fall through to respawn
            sidecar?.destroy()
        }

        // VPN mode: the fd stays in-process — ProcessBuilder closes inherited
        // fds, so the TUN is bridged by hev-socks5-tunnel (JNI) to mihomo's
        // mixed port and the core always runs proxy-only
        val tunFd = host.openSidecarTun(
            SidecarTunSpec(
                exclusions = routeExclusions(),
                perAppEnabled = overrides.perAppEnabled,
                perAppInclude = overrides.perAppInclude,
                perAppPackages = overrides.perAppPackages,
                allowBypass = Settings.allowBypass,
            ),
        )
        if (tunFd != null) {
            val ok = runCatching { TProxyService.start(context, tunFd, MIXED_PORT) }.getOrDefault(false)
            if (!ok) {
                Log.e(TAG, "hev tun bridge failed to start")
                AppLog.log("vpn", "hev TUN 桥启动失败")
                error("TUN 桥接启动失败")
            }
            AppLog.log("vpn", "hev TUN 桥已启动 (fd=$tunFd → 127.0.0.1:$MIXED_PORT)")
        }
        activeTunFd = tunFd

        // stale stored configs may still carry a tun block — strip it
        configFile.writeText(stripTun(config))

        val process = SidecarProcess(
            context,
            "libmihomo.so",
            listOf("-d", workDir.absolutePath, "-f", configFile.absolutePath),
            workDir,
            onExit = { code ->
                Log.e(TAG, "mihomo exited unexpectedly: $code")
                AppLog.log("mihomo", "进程异常退出 code=$code")
                activeTunFd = null
                Holder.instance = null
                host.onCoreRequestStop()
            },
        )
        val spawnAtMs = System.currentTimeMillis()
        process.start()
        sidecar = process
        Holder.instance = this
        AppLog.log("mihomo", "进程已启动, 等待 API 就绪…")

        // wait for the REST API to come up: raw TCP reachability first (auth /
        // HTTP failures must not be mistaken for "not ready"), then probe the
        // real endpoint once and surface its error for diagnosis
        var ready = false
        var socketErr: String? = null
        repeat(READY_POLLS) {
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", API_PORT), 600)
                }
                ready = true
                return@repeat
            } catch (e: Exception) {
                socketErr = e.message
            }
            delay(READY_INTERVAL_MS)
        }
        if (!ready) {
            Log.e(TAG, "mihomo API socket unreachable: $socketErr")
            AppLog.log("mihomo", "API 端口不可达: $socketErr")
            error("mihomo 启动超时(详见 ${configFile.parentFile}/libmihomo.so.log)")
        }
        AppLog.log("mihomo", "API 就绪 (${"%.1f".format((System.currentTimeMillis() - spawnAtMs) / 1000.0)}s)")
        runCatching { api.versionOrThrow() }.onFailure {
            AppLog.log("mihomo", "API 探测失败: ${it.message}")
            Log.w(TAG, "version probe failed", it)
        }
        startTrafficPoller()
        applySelection(overrides)
    }

    /** Feeds the persistent notification with per-second traffic. */
    private fun startTrafficPoller() {
        if (trafficJob?.isActive == true) return
        var lastDown = -1L
        var lastUp = -1L
        trafficJob = scope.launch {
            while (isActive) {
                delay(2000)
                val conn = runCatching { api.connections() }.getOrNull() ?: continue
                val down = conn["downloadTotal"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toLongOrNull() } ?: continue
                val up = conn["uploadTotal"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toLongOrNull() } ?: continue
                if (lastDown >= 0 && down >= lastDown && up >= lastUp) {
                    host.onCoreTraffic((up - lastUp) / 2, (down - lastDown) / 2)
                }
                lastDown = down
                lastUp = up
            }
        }
    }

    override fun pause() {}
    override fun wake() {}
    override fun needWifiState() = false

    override suspend fun shutdown() {
        Holder.instance = null
        trafficJob?.cancel()
        trafficJob = null
        TProxyService.stop()
        activeTunFd = null
        sidecar?.destroy()
        sidecar = null
    }

    /** Re-apply whatever ConfigStore currently holds (UI-driven refresh). */
    suspend fun refreshFromConfigStore() {
        val content = com.interstellar.proxy.data.ConfigStore.readActiveConfig() ?: return
        applyConfig(content, lastOverrides ?: CoreOverrides(false, false, true, emptySet()))
    }

    /**
     * Full process restart instead of an API hot reload — for changes the
     * running process can't pick up (geodata files are read at spawn).
     */
    suspend fun restartFromConfigStore() {
        val content = com.interstellar.proxy.data.ConfigStore.readActiveConfig() ?: return
        sidecar?.destroy()
        sidecar = null
        applyConfig(content, lastOverrides ?: CoreOverrides(false, false, true, emptySet()))
    }

    // ---- helpers ----

    /** mihomo select groups have no config default — apply via API (store-selected persists it). */
    private suspend fun applySelection(overrides: CoreOverrides) {
        val tag = overrides.selectedTag?.takeIf { it.isNotBlank() } ?: return
        runCatching { api.select(ConfigBuilder.GROUP_TAG, tag) }
    }

    private fun routeExclusions(): List<String> =
        MihomoConfigBuilder.routeExclusions(SubscriptionRepository.activeNodes())

    private fun stripTun(config: String): String {
        // headless / proxy mode: drop the tun block entirely
        val lines = config.split('\n').toMutableList()
        val start = lines.indexOfFirst { it.startsWith("tun:") }
        if (start >= 0) {
            var end = lines.size
            for (i in start + 1 until lines.size) {
                if (lines[i].isNotBlank() && !lines[i].startsWith("  ")) {
                    end = i
                    break
                }
            }
            lines.subList(start, end).clear()
        }
        return lines.joinToString("\n")
    }

    companion object {
        private const val TAG = "MihomoCore"
        // NOT 9090: every clash-family app defaults there, and a concurrently
        // running one (CMFA/FlClash…) silently hijacks our API client, group
        // polling and readiness probe — we'd read ITS groups as ours
        const val API_PORT = 19090
        /** must match ConfigBuilder.BuildOptions.mixedPort default */
        const val MIXED_PORT = 2080
        private const val READY_POLLS = 20
        private const val READY_INTERVAL_MS = 500L
    }

    /** Process-wide handle so the UI can hot-reload the running engine. */
    object Holder {
        @Volatile
        var instance: MihomoCore? = null
    }
}
