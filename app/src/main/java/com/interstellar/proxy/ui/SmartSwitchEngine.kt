package com.interstellar.proxy.ui

import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.core.AppLog
import com.interstellar.proxy.core.DirectPing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Smart switch engine — automatic node selection driven by exit quality.
 *
 * Loop (self-gated to Started + smart mode):
 *  1. patrol: real-latency probe of the CURRENT exit (204 through the local
 *     inbound). Healthy ≤ GOOD_MS → just refresh the cache entry.
 *  2. unhealthy → reselection round:
 *     a. fast TCP ping over the whole pool (bounded, concurrent, off-tunnel
 *        via DirectPing) → candidates with ping < PING_MAX_MS
 *     b. real-latency data: kernels with a control API get a group url-test
 *        snapshot; otherwise (Xray) candidates are verified by switching
 *        and probing (bounded attempts)
 *     c. pick the lowest real latency < REAL_MAX_MS; probe order follows the
 *        persisted delay cache (last round's results sort the next round)
 *  3. guard rails: switch cooldown, round-duration alert, thin-candidate
 *     alert, no-candidate alert — surfaced via [state] and AppLog ("smart").
 *
 * The delay cache lives in filesDir/smart_cache.json and doubles as the
 * "上次延迟记录" for both ordering and the node-page display.
 */
class SmartSwitchEngine(
    private val isActive: () -> Boolean,
    private val currentTag: () -> String?,
    private val socksPort: Int,
    private val useSocksProxy: () -> Boolean,
    /** Request kernel group url-test + wait for settle; null if unsupported. */
    private val requestKernelDelays: (suspend () -> Map<String, Int>?)?,
    /** Hot-switch the core onto [tag] (API or restart). */
    private val applySwitch: suspend (tag: String) -> Boolean,
    private val pool: () -> List<com.interstellar.proxy.data.model.ProxyNode>,
    private val onStateChanged: (SmartState) -> Unit,
) {
    data class SmartState(
        val active: Boolean = false,
        val phase: SmartPhase = SmartPhase.Idle,
        val currentDelayMs: Int = 0,
        /** The node smart mode currently rides on (what the card should show). */
        val currentTag: String? = null,
        val candidateCount: Int = 0,
        val lastSwitchTo: String? = null,
        val lastSwitchAt: Long = 0,
        val alert: SmartAlert? = null,
    )

    /** Engine phase — UI-localized via LocalizedNames.smartPhaseText. */
    sealed interface SmartPhase {
        data object Idle : SmartPhase
        data object Patrolling : SmartPhase
        data class PatrolError(val brief: String) : SmartPhase
        data class Healthy(val delayMs: Int) : SmartPhase
        data object PingScan : SmartPhase
        data object RealTest : SmartPhase
        data object Cooldown : SmartPhase
        data class Switched(val delayMs: Int) : SmartPhase
        data class Keep(val delayMs: Int) : SmartPhase
        data object Failed : SmartPhase
    }

    /** Guard-rail outcome surfaced on the dashboard status line. */
    sealed interface SmartAlert {
        data object NoPool : SmartAlert
        data object NoCandidates : SmartAlert
        data object NoQualified : SmartAlert
        data class RoundTooLong(val seconds: Int) : SmartAlert
    }

    companion object {
        private const val PATROL_INTERVAL_MS = 30_000L
        private const val FIRST_PATROL_DELAY_MS = 8_000L
        private const val GOOD_MS = 300            // patrol threshold
        private const val REAL_MAX_MS = 300        // candidate quality bar
        private const val PING_MAX_MS = 200        // fast-pool bar
        private const val PING_TIMEOUT_MS = 3_000
        private const val REAL_PROBE_TIMEOUT_S = 5L
        private const val SWITCH_COOLDOWN_MS = 20_000L
        private const val ROUND_TOO_LONG_MS = 40_000L
        private const val MIN_CANDIDATES_WARN = 3
        private const val VERIFY_ATTEMPTS = 5       // sequential switch-verify (API-less cores)
        private const val CACHE_FILE = "smart_cache.json"
        private const val TEST_URL = "https://www.gstatic.com/generate_204"
    }

    @Volatile
    private var started = false

    /** tag → (delayMs, epochSec); persisted, sorted ascending by delay. */
    private val cache = linkedMapOf<String, Pair<Int, Long>>()

    private var state = SmartState()
        set(value) {
            field = value
            onStateChanged(value)
        }

    /** Per-probe client: the proxy TYPE can change when the user switches cores. */
    private fun probeClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(REAL_PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(REAL_PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .proxy(
            Proxy(
                if (useSocksProxy()) Proxy.Type.SOCKS else Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", socksPort),
            ),
        )
        .build()

    fun start() {
        if (started) return
        started = true
        loadCache()
        AppLog.log(
            "smart",
            "引擎已启动: 巡检间隔 ${PATROL_INTERVAL_MS / 1000}s · 正常阈值 ${GOOD_MS}ms · 候选门槛 ping<${PING_MAX_MS}ms 实测<${REAL_MAX_MS}ms",
        )
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            delay(FIRST_PATROL_DELAY_MS)
            while (started) {
                try {
                    patrolOnce()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.log("smart", "巡检循环异常: ${e.message?.take(48) ?: e.javaClass.simpleName}")
                    state = state.copy(phase = SmartPhase.PatrolError(e.message?.take(24) ?: e.javaClass.simpleName))
                }
                delay(PATROL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        AppLog.log("smart", "引擎已停止")
        state = state.copy(active = false, phase = SmartPhase.Idle)
    }

    // ---- patrol ----

    private suspend fun patrolOnce() {
        if (!isActive()) {
            if (state.active) state = state.copy(active = false, phase = SmartPhase.Idle)
            return
        }
        state = state.copy(active = true, phase = SmartPhase.Patrolling, currentTag = currentTag())
        val tag = currentTag()
        val real = probeExit()
        state = state.copy(currentDelayMs = real)
        if (tag != null && real in 1..GOOD_MS) {
            record(tag, real)
            state = state.copy(phase = SmartPhase.Healthy(real))
            AppLog.log("smart", "巡检正常: $tag · ${real}ms")
            return
        }
        if (real > GOOD_MS || real <= 0) {
            AppLog.log(
                "smart",
                "巡检异常: ${tag ?: "无当前节点"} · ${if (real > 0) "延迟 ${real}ms 超过阈值 ${GOOD_MS}ms" else "出口不可用"} → 开始筛选备选",
            )
            reselect(real <= 0)
        }
    }

    // ---- reselection round ----

    private suspend fun reselect(currentDead: Boolean) {
        val roundStart = System.currentTimeMillis()
        val nodes = pool()
        if (nodes.isEmpty()) {
            state = state.copy(alert = SmartAlert.NoPool)
            AppLog.log("smart", "筛选中止: 无可用节点池")
            return
        }
        val tags = com.interstellar.proxy.data.config.ConfigBuilder.tagsFor(nodes)

        state = state.copy(phase = SmartPhase.PingScan)
        val pings = pingPool(nodes, tags)
        val byTag = tags.zip(pings).toMap()
        val candidates = tags.filter { tag ->
            (byTag[tag] ?: Int.MAX_VALUE) in 1 until PING_MAX_MS
        }
        val fastestPing = pings.filter { it in 1 until PING_TIMEOUT_MS }.minOrNull()
        AppLog.log(
            "smart",
            "Ping 扫描完成: ${nodes.size} 个节点 → ${candidates.size} 个候选 (<${PING_MAX_MS}ms)" +
                (fastestPing?.let { ", 最快 ${it}ms" } ?: ""),
        )
        // last round's real delays decide this round's probe order
        val ordered = candidates.sortedBy { tag -> cache[tag]?.first ?: Int.MAX_VALUE }
        state = state.copy(candidateCount = candidates.size)
        if (candidates.isEmpty()) {
            state = state.copy(alert = SmartAlert.NoCandidates)
            AppLog.log("smart", "筛选中止: Ping 后无低延迟备选节点 (链路质量差?)")
            return
        }
        if (candidates.size < MIN_CANDIDATES_WARN) {
            AppLog.log("smart", "备选节点过少: 仅 ${candidates.size} 个")
        }

        state = state.copy(phase = SmartPhase.RealTest)
        var chosen: Pair<String, Int>? = null
        val kernelDelays = requestKernelDelays?.invoke()
        if (kernelDelays != null) {
            // API cores: the url-test snapshot already carries real delays
            kernelDelays.forEach { (tag, delay) -> if (delay in 1 until 65_000) record(tag, delay) }
            chosen = ordered
                .mapNotNull { tag -> kernelDelays[tag]?.takeIf { it in 1 until REAL_MAX_MS }?.let { tag to it } }
                .minByOrNull { it.second }
            AppLog.log(
                "smart",
                "url-test 实测: ${kernelDelays.size} 条延迟, 候选中达标 ${ordered.count { (kernelDelays[it] ?: 0) in 1 until REAL_MAX_MS }} 个 (<${REAL_MAX_MS}ms)",
            )
        }
        if (chosen == null && kernelDelays == null) {
            // API-less (Xray): switch-verify candidates in cached order
            for (tag in ordered.take(VERIFY_ATTEMPTS)) {
                if (!applySwitch(tag)) continue
                state = state.copy(currentTag = tag)
                val real = probeExit()
                record(tag, real)
                AppLog.log("smart", "切换验证: $tag · ${if (real > 0) "${real}ms" else "不可用"}")
                if (real in 1 until REAL_MAX_MS) {
                    chosen = tag to real
                    break
                }
                if (!currentDead) {
                    // don't strand traffic on a bad verify — cooldown guard
                    // keeps the next patrol from thrashing
                    break
                }
            }
        }

        val elapsed = System.currentTimeMillis() - roundStart
        when {
            chosen == null -> {
                state = state.copy(alert = SmartAlert.NoQualified, phase = SmartPhase.Failed)
                AppLog.log("smart", "本轮筛选结束: 未找到延迟 <${REAL_MAX_MS}ms 的节点 (耗时 ${elapsed / 1000}s)")
            }

            else -> {
                val (tag, delay) = chosen
                val current = currentTag()
                if (tag != current) {
                    if (System.currentTimeMillis() - state.lastSwitchAt < SWITCH_COOLDOWN_MS &&
                        !currentDead
                    ) {
                        state = state.copy(phase = SmartPhase.Cooldown)
                        AppLog.log("smart", "冷却中 (距上次切换不足 ${SWITCH_COOLDOWN_MS / 1000}s), 暂不切换 → $tag (${delay}ms)")
                        return
                    }
                    if (applySwitch(tag)) {
                        record(tag, delay)
                        state = state.copy(
                            phase = SmartPhase.Switched(delay),
                            currentTag = tag,
                            lastSwitchTo = tag,
                            lastSwitchAt = System.currentTimeMillis(),
                            currentDelayMs = delay,
                        )
                        AppLog.log("smart", "已切换: ${current ?: "(无)"} → $tag (${delay}ms)")
                    } else {
                        AppLog.log("smart", "切换失败: $tag (内核 API 不可达或重启失败)")
                    }
                } else {
                    record(tag, delay)
                    state = state.copy(phase = SmartPhase.Keep(delay))
                    AppLog.log("smart", "保持当前节点: $tag (${delay}ms)")
                }
            }
        }
        if (elapsed > ROUND_TOO_LONG_MS) {
            state = state.copy(alert = SmartAlert.RoundTooLong((elapsed / 1000).toInt()))
            AppLog.log("smart", "本轮筛选耗时过长 (${elapsed / 1000}s)")
        }
        saveCache()
    }

    // ---- probes ----

    /** Real end-to-end exit latency via the local inbound; -1 = failed. */
    private fun probeExit(): Int {
        val startedAt = System.currentTimeMillis()
        return runCatching {
            probeClient().newCall(Request.Builder().url(TEST_URL).head().build()).execute().use {
                if (it.isSuccessful) (System.currentTimeMillis() - startedAt).toInt() else -1
            }
        }.getOrDefault(-1)
    }

    /** Direct TCP ping of every node (bounded concurrency), order-aligned with [tags]. */
    private suspend fun pingPool(
        nodes: List<com.interstellar.proxy.data.model.ProxyNode>,
        tags: List<String>,
    ): List<Int> = coroutineScope {
        // the engine only runs while connected — a plain connect would
        // handshake with our own tun stack (~3-4ms) for every node
        DirectPing.warmup(1_500)
        val results = ConcurrentHashMap<Int, Int>()
        val semaphore = kotlinx.coroutines.sync.Semaphore(16)
        val done = AtomicInteger()
        nodes.forEachIndexed { idx, node ->
            launch {
                semaphore.withPermit {
                    results[idx] = runCatching {
                        DirectPing.tcpConnect(node.server, node.port, PING_TIMEOUT_MS)
                            .coerceAtMost(PING_TIMEOUT_MS - 1)
                    }.getOrDefault(PING_TIMEOUT_MS)
                    done.incrementAndGet()
                }
            }
        }
        // wait for all probes
        while (done.get() < nodes.size) kotlinx.coroutines.delay(50)
        List(nodes.size) { results[it] ?: PING_TIMEOUT_MS }
    }

    // ---- cache ----

    private fun record(tag: String, delay: Int) {
        if (delay <= 0) return
        cache[tag] = delay to System.currentTimeMillis() / 1000
    }

    private fun cacheFile(): File =
        File(InterstellarApplication.application.filesDir, CACHE_FILE)

    private fun loadCache() {
        runCatching {
            val obj = JSONObject(cacheFile().readText())
            obj.keys().forEach { tag ->
                val entry = obj.optJSONObject(tag) ?: return@forEach
                val delay = entry.optInt("delay", 0)
                if (delay > 0) cache[tag] = delay to entry.optLong("ts", 0)
            }
            sortCache()
        }
    }

    private fun saveCache() {
        runCatching {
            sortCache()
            val obj = JSONObject()
            cache.forEach { (tag, v) -> obj.put(tag, JSONObject().put("delay", v.first).put("ts", v.second)) }
            cacheFile().writeText(obj.toString())
        }
    }

    private fun sortCache() {
        val sorted = cache.entries.sortedBy { it.value.first }
        cache.clear()
        sorted.forEach { (k, v) -> cache[k] = v }
    }

    /** Ordered tags by cached delay (ascending) — the node page's "智能" order. */
    fun cachedOrder(): List<String> = cache.keys.toList()

    fun cachedDelay(tag: String): Int? = cache[tag]?.first
}
