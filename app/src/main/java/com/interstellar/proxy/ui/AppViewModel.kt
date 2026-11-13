package com.interstellar.proxy.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interstellar.proxy.constant.Action
import com.interstellar.proxy.constant.Status
import com.interstellar.proxy.data.ConfigStore
import com.interstellar.proxy.data.CustomRulesStore
import com.interstellar.proxy.data.DnsOverridesStore
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.data.SubscriptionRepository
import com.interstellar.proxy.data.config.ConfigBuilder
import com.interstellar.proxy.data.model.CustomRouteRule
import com.interstellar.proxy.data.model.DnsOverrideEntry
import com.interstellar.proxy.data.net.SubscriptionFetcher
import com.interstellar.proxy.data.subscription.SubscriptionParser
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.StatusMessage
import com.interstellar.proxy.core.ClashApiClient
import com.interstellar.proxy.core.CoreGroup
import com.interstellar.proxy.core.CoreGroupItem
import com.interstellar.proxy.core.CoreKind
import com.interstellar.proxy.core.DirectPing
import com.interstellar.proxy.core.MihomoCore
import com.interstellar.proxy.utils.CommandClient
import com.interstellar.proxy.utils.CommandTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.interstellar.proxy.data.subscription.arr
import com.interstellar.proxy.data.subscription.str
import com.interstellar.proxy.data.subscription.strList
import java.security.SecureRandom

data class SpeedState(
    val uplinkPerSecond: Long = 0,
    val downlinkPerSecond: Long = 0,
    val uplinkTotal: Long = 0,
    val downlinkTotal: Long = 0,
)

data class ClashModeState(
    val modes: List<String> = emptyList(),
    val current: String = "rule",
)

data class SplitRuleStatus(
    val hasEnabledRules: Boolean = false,
    /** Master switch on the nodes page. Independent of auto vs a locked node. */
    val masterEnabled: Boolean = true,
    /** Keyword-filter rules are actually in the running/generated config. */
    val active: Boolean = false,
)

/** Exit-IP probe lifecycle for the dashboard 网络探测 card. */
sealed interface ProbeState {
    data object Idle : ProbeState
    data object Running : ProbeState
    data class Done(val result: com.interstellar.proxy.data.net.NetProbe.Result) : ProbeState
    data class Failed(val message: String) : ProbeState
}

/** Transient feedback pill (subscription updates etc.). */
data class UiToast(val text: String, val kind: Kind) {
    enum class Kind { Success, Error }
}

/**
 * App-wide state holder: box status via the local command socket,
 * subscriptions, and start/stop orchestration.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    /** Localized string following the CURRENT language (live, no restart needed). */
    private fun str(@androidx.annotation.StringRes id: Int): String =
        com.interstellar.proxy.ktx.AppLanguage.getString(getApplication(), id)

    private fun str(@androidx.annotation.StringRes id: Int, vararg formatArgs: Any): String =
        com.interstellar.proxy.ktx.AppLanguage.getString(getApplication(), id, *formatArgs)

    private val _status = MutableStateFlow(Status.Stopped)
    val status: StateFlow<Status> = _status

    private val _connectedAt = MutableStateFlow(0L)
    val connectedAt: StateFlow<Long> = _connectedAt

    private val _speed = MutableStateFlow(SpeedState())
    val speed: StateFlow<SpeedState> = _speed

    /** Per-second (down, up) samples for the live chart, newest last. */
    private val _history = MutableStateFlow<List<Pair<Long, Long>>>(emptyList())
    val history: StateFlow<List<Pair<Long, Long>>> = _history

    private val _clashMode = MutableStateFlow(ClashModeState())
    val clashMode: StateFlow<ClashModeState> = _clashMode

    /**
     * Routing mode as the UI sees it ("rule" | "global" | "direct"). The mode
     * is BAKED into the generated config (route.final / CN bypass rules), so
     * this flow is driven by the persisted setting plus optimistic updates —
     * the core's clash-mode callback alone can't reflect it.
     */
    private val _routingMode = MutableStateFlow(Settings.outboundMode.name.lowercase())
    val routingMode: StateFlow<String> = _routingMode

    private val _groups = MutableStateFlow<List<CoreGroup>>(emptyList())
    val groups: StateFlow<List<CoreGroup>> = _groups

    /**
     * Groups parsed from the on-disk active config — what the page shows while
     * the core is stopped (live groups only exist once it runs).
     */
    private val _staticGroups = MutableStateFlow<List<CoreGroup>>(emptyList())
    val staticGroups: StateFlow<List<CoreGroup>> = _staticGroups

    /** Observable engine kind — drives the dashboard core segmented control. */
    private val _coreKind = MutableStateFlow(Settings.coreKind)
    val coreKind: StateFlow<CoreKind> = _coreKind

    /** Active connection count from the mihomo API (dashboard core card). */
    private val _mihomoConnectionCount = MutableStateFlow(0)
    val mihomoConnectionCount: StateFlow<Int> = _mihomoConnectionCount

    /** App-proxy scope as the UI sees it (drives the dashboard status row). */
    data class ProxyScope(val whitelist: Boolean, val count: Int) {
        /** Per-app routing armed at all (label text is decided by the caller's locale). */
        val on: Boolean
            get() = com.interstellar.proxy.data.Settings.perAppProxyEnabled
    }

    private val _proxyScope = MutableStateFlow(readProxyScope())
    val proxyScope: StateFlow<ProxyScope> = _proxyScope

    /** True while a rule/geodata file update is in flight (spinner in the UI). */
    private val _ruleFilesUpdating = MutableStateFlow(false)
    val ruleFilesUpdating: StateFlow<Boolean> = _ruleFilesUpdating

    private fun readProxyScope() = ProxyScope(
        whitelist = Settings.perAppProxyMode == Settings.PER_APP_PROXY_INCLUDE,
        count = Settings.perAppProxyList.size,
    )

    fun setProxyScope(mode: String) {
        when (mode) {
            "all" -> Settings.perAppProxyEnabled = false
            "whitelist" -> {
                Settings.perAppProxyMode = Settings.PER_APP_PROXY_INCLUDE
                Settings.perAppProxyEnabled = true
            }

            "blacklist" -> {
                Settings.perAppProxyMode = Settings.PER_APP_PROXY_EXCLUDE
                Settings.perAppProxyEnabled = true
            }
        }
        _proxyScope.value = readProxyScope()
        refreshProxyConfig()
    }

    // ---- simple routing rules ----

    private val _simpleRules = MutableStateFlow(com.interstellar.proxy.data.SimpleRulesStore.rules.toList())
    val simpleRules: StateFlow<List<com.interstellar.proxy.data.SimpleRouteRule>> = _simpleRules

    fun upsertSimpleRule(rule: com.interstellar.proxy.data.SimpleRouteRule) {
        com.interstellar.proxy.data.SimpleRulesStore.upsert(rule)
        _simpleRules.value = com.interstellar.proxy.data.SimpleRulesStore.rules.toList()
        refreshProxyConfig()
    }

    fun removeSimpleRule(id: String) {
        com.interstellar.proxy.data.SimpleRulesStore.remove(id)
        _simpleRules.value = com.interstellar.proxy.data.SimpleRulesStore.rules.toList()
        refreshProxyConfig()
    }

    fun setSimpleRuleEnabled(id: String, enabled: Boolean) {
        com.interstellar.proxy.data.SimpleRulesStore.setEnabled(id, enabled)
        _simpleRules.value = com.interstellar.proxy.data.SimpleRulesStore.rules.toList()
        refreshProxyConfig()
    }

    /** id/tag/name triples of the current node pool for the rule picker. */
    fun nodePickerEntries(): List<Triple<String, String, String>> {
        val pool = SubscriptionRepository.poolOf(
            _subscriptions.value,
            _activeSubscriptionId.value,
            _mixEnabled.value,
            _mixSubscriptionIds.value,
        )
        return ConfigBuilder.tagsFor(pool).zip(pool) { tag, node -> Triple(node.id, tag, node.name) }
    }

    /** tag → latest url-test delay (pushed via the outbounds stream). */
    private val _delays = MutableStateFlow<Map<String, Int>>(emptyMap())
    val delays: StateFlow<Map<String, Int>> = _delays

    /** True while a group url-test is in flight (for UI spinners). */
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing

    /** Batch test progress (done, total); null while idle. */
    private val _testProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val testProgress: StateFlow<Pair<Int, Int>?> = _testProgress

    private val _pingProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val pingProgress: StateFlow<Pair<Int, Int>?> = _pingProgress

    /** Epoch seconds when the current url-test run started (0 = idle). */
    @Volatile private var testStartEpoch = 0L

    /** Serializes kernel url-test runs (manual button vs smart engine). */
    private val kernelUrlTestMutex = kotlinx.coroutines.sync.Mutex()

    /** True while the in-flight kernel url-test run belongs to the manual button. */
    @Volatile private var kernelTestManual = false

    /** Member tags the in-flight kernel run covers (null = full outbound set). */
    @Volatile private var kernelTestScopeTags: Set<String>? = null

    /** sing-box's per-node url-test HTTP timeout (constant.TCPTimeout, hardcoded in the kernel). */
    private val PER_NODE_TEST_TIMEOUT_MS = 15_000L

    private val _subscriptions =
        MutableStateFlow(SubscriptionRepository.subscriptions.toList())
    val subscriptions: StateFlow<List<SubscriptionRepository.Subscription>> = _subscriptions

    private val _activeSubscriptionId =
        MutableStateFlow(SubscriptionRepository.activeSubscriptionId)
    val activeSubscriptionId: StateFlow<String> = _activeSubscriptionId

    /** Mix: node pool = union of the checked subscriptions. */
    private val _mixEnabled = MutableStateFlow(Settings.mixEnabled)
    val mixEnabled: StateFlow<Boolean> = _mixEnabled

    /** Global raw-config switch: the active subscription's config goes to the core verbatim. */
    private val _useRawConfig = MutableStateFlow(Settings.useRawConfigEnabled)
    val useRawConfig: StateFlow<Boolean> = _useRawConfig

    fun setUseRawConfig(enabled: Boolean) {
        if (enabled == _useRawConfig.value) return
        _useRawConfig.value = enabled
        Settings.useRawConfigEnabled = enabled
        // raw mode is single-subscription by definition — mix cannot coexist
        if (enabled && Settings.mixEnabled) {
            Settings.mixEnabled = false
            _mixEnabled.value = false
        }
        refreshProxyConfig()
    }

    private val _mixSubscriptionIds = MutableStateFlow(Settings.mixSubscriptionIds)
    val mixSubscriptionIds: StateFlow<Set<String>> = _mixSubscriptionIds

    private val _selectedOutboundTag = MutableStateFlow(Settings.selectedOutboundTag)
    val selectedOutboundTag: StateFlow<String> = _selectedOutboundTag

    /** Nodes page layout: grid (default) or list; persisted. */
    private val _nodesGridView = MutableStateFlow(Settings.nodesGridView)
    val nodesGridView: StateFlow<Boolean> = _nodesGridView

    fun setNodesGridView(value: Boolean) {
        if (value == _nodesGridView.value) return
        Settings.nodesGridView = value
        _nodesGridView.value = value
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** True while a subscription URL/text import is in flight (dialog spinner). */
    private val _addingSub = MutableStateFlow(false)
    val addingSub: StateFlow<Boolean> = _addingSub

    private var addSubJob: kotlinx.coroutines.Job? = null

    /** Last import failure, surfaced INSIDE the add dialog (modal covers page toasts). */
    private val _addSubError = MutableStateFlow<String?>(null)
    val addSubError: StateFlow<String?> = _addSubError

    // ---- smart switch (智能模式) ----

    private val _smartState = MutableStateFlow(SmartSwitchEngine.SmartState())
    val smartState: StateFlow<SmartSwitchEngine.SmartState> = _smartState

    val smartEngine: SmartSwitchEngine by lazy {
        SmartSwitchEngine(
            isActive = {
                _status.value == Status.Started &&
                    Settings.selectedOutboundTag == com.interstellar.proxy.data.config.ConfigBuilder.SMART_TAG
            },
            currentTag = { smartCurrentTag() },
            socksPort = 2080,
            useSocksProxy = { Settings.coreKind == CoreKind.XRAY },
            requestKernelDelays = { requestKernelGroupDelays() },
            applySwitch = { tag -> applySmartSwitch(tag) },
            pool = {
                SubscriptionRepository.poolOf(
                    _subscriptions.value,
                    _activeSubscriptionId.value,
                    _mixEnabled.value,
                    _mixSubscriptionIds.value,
                )
            },
            // 智能行为细节走 AppLog("smart") 在日志页呈现; 严重状态由首页 SmartStatusLine(state.alert) 呈现
            onStateChanged = { _smartState.value = it },
        )
    }

    /** The node smart mode currently rides on (falls back to the live group selection). */
    private fun smartCurrentTag(): String? {
        Settings.smartActiveTag.takeIf { it.isNotBlank() }?.let { return it }
        return _groups.value.find { it.tag == GROUP_TAG }?.selected?.takeIf { it.isNotBlank() }
    }

    /** sing-box/mihomo: one REST batch (libbox command fallback); Xray has no API → null. */
    private suspend fun requestKernelGroupDelays(): Map<String, Int>? {
        return when (Settings.coreKind) {
            CoreKind.SINGBOX -> {
                // REST batch first — one bounded call; the libbox command +
                // stream settle stays as fallback
                val result = clashApi.groupDelay(ConfigBuilder.AUTO_TAG)
                if (result != null) {
                    applyDelays(result)
                    _delays.value
                } else if (runKernelUrlTest(manual = false)) {
                    _delays.value
                } else {
                    null
                }
            }

            CoreKind.MIHOMO -> {
                val result = clashApi.groupDelay(ConfigBuilder.AUTO_TAG) ?: return null
                applyDelays(result)
                _delays.value
            }

            CoreKind.XRAY -> null
        }
    }

    /** Hot-switch the running core; persists smartActiveTag on success. */
    private suspend fun applySmartSwitch(tag: String): Boolean {
        val ok = when (Settings.coreKind) {
            CoreKind.MIHOMO -> runCatching { clashApi.select(ConfigBuilder.GROUP_TAG, tag) }.isSuccess

            CoreKind.SINGBOX -> runCatching {
                CommandTarget.standaloneClient().selectOutbound(ConfigBuilder.GROUP_TAG, tag)
            }.isSuccess

            CoreKind.XRAY -> {
                Settings.smartActiveTag = tag
                SubscriptionRepository.regenerateActiveConfig()
                runCatching {
                    com.interstellar.proxy.core.XrayCore.Holder.instance?.restartFromConfigStore()
                }.isSuccess
            }
        }
        if (ok) {
            Settings.smartActiveTag = tag
        }
        return ok
    }

    /** User picked 智能 in the node page: mark mode, bake config, kick the engine. */
    fun selectSmartMode() {
        if (Settings.selectedOutboundTag == ConfigBuilder.SMART_TAG) return
        Settings.selectedOutboundTag = ConfigBuilder.SMART_TAG
        _selectedOutboundTag.value = ConfigBuilder.SMART_TAG
        refreshSplitRuleStatus()
        viewModelScope.launch(Dispatchers.IO) {
            if (_status.value == Status.Started) {
                // hot-apply the effective selection (smartActiveTag / auto)
                Settings.smartActiveTag.takeIf { it.isNotBlank() }?.let { applySmartSwitch(it) }
            } else {
                SubscriptionRepository.regenerateActiveConfig()
            }
            smartEngine.start()
        }
    }

    /** Abort an in-flight subscription import (dialog 取消). */
    fun cancelAddSubscription() {
        addSubJob?.cancel()
        addSubJob = null
        _addingSub.value = false
        _addSubError.value = null
        _message.value = str(com.interstellar.proxy.R.string.vm_import_cancelled)
    }

    /** True while any subscription refresh triggered by pull-to-refresh runs. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _probe = MutableStateFlow<ProbeState>(ProbeState.Idle)
    val probe: StateFlow<ProbeState> = _probe

    private val _toast = MutableStateFlow<UiToast?>(null)
    val toast: StateFlow<UiToast?> = _toast

    private var toastClearJob: Job? = null

    /** True while the concurrent TCP ping sweep runs. */
    private val _pinging = MutableStateFlow(false)
    val pinging: StateFlow<Boolean> = _pinging

    private val delaysMutex = kotlinx.coroutines.sync.Mutex()

    fun showToast(text: String, kind: UiToast.Kind) {
        _toast.value = UiToast(text, kind)
        toastClearJob?.cancel()
        toastClearJob = viewModelScope.launch {
            delay(2800)
            _toast.value = null
        }
    }

    private val _customRules = MutableStateFlow(CustomRulesStore.rules.toList())
    val customRules: StateFlow<List<CustomRouteRule>> = _customRules

    private val _dnsOverrides = MutableStateFlow(DnsOverridesStore.entries.toList())
    val dnsOverrides: StateFlow<List<DnsOverrideEntry>> = _dnsOverrides

    private val _splitRuleStatus = MutableStateFlow(computeSplitRuleStatus())
    val splitRuleStatus: StateFlow<SplitRuleStatus> = _splitRuleStatus

    private var pollJob: Job? = null
    private var startingWatchdog: Job? = null
    private var stoppedReceiver: BroadcastReceiver? = null
    private var autoTested = false
    @Volatile private var probing = false

    /** Set by onConnected while probing: the headless command socket is up. */
    @Volatile private var probeSocketUp = false

    private companion object {
        const val GROUP_TAG = "proxy"
        const val STARTING_TIMEOUT_MS = 15_000L
        const val SOCKET_DROP_TIMEOUT_MS = 400L

        /** Sentinel delay for timed-out / unreachable nodes (ping & url-test). */
        const val TIMEOUT_DELAY = 65535
    }

    private val commandClient = CommandClient(
        viewModelScope,
        listOf(
            CommandClient.ConnectionType.Status,
            CommandClient.ConnectionType.Groups,
            CommandClient.ConnectionType.ClashMode,
            // delay updates stream through outbounds, not the groups snapshot
            CommandClient.ConnectionType.Outbounds,
        ),
        object : CommandClient.Handler {
            override fun onConnected() {
                if (probing) {
                    // headless probe: don't promote the UI to connected, just
                    // flag that the command socket is reachable
                    probeSocketUp = true
                    return
                }
                // App relaunch while the core is already up.
                if (_status.value == Status.Stopped) {
                    markStarted()
                }
            }

            override fun onDisconnected() {
                autoTested = false
                if (!probing) applySocketDrop()
            }

            override fun onConnectionError(kind: CommandClient.ConnectionErrorKind, message: String) {
                if (!probing) applySocketDrop()
            }

            override fun updateStatus(status: StatusMessage) {
                if (probing) return
                // Status ticks only after the tun is actually running — this is
                // what promotes Starting → Started, so a socket bounce during
                // boot cannot flash Stopped.
                if (_status.value == Status.Starting) {
                    markStarted()
                }
                _speed.value = SpeedState(
                    uplinkPerSecond = status.uplink,
                    downlinkPerSecond = status.downlink,
                    uplinkTotal = status.uplinkTotal,
                    downlinkTotal = status.downlinkTotal,
                )
                val sample = status.downlink to status.uplink
                _history.value = (_history.value + sample).takeLast(60)
            }

            override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
                _groups.value = newGroups.map(::convertGroup)
                refreshSplitRuleStatus()
            }

            override fun updateOutbounds(outbounds: List<io.nekohasekai.libbox.OutboundGroupItem>) {
                val map = _delays.value.toMutableMap()
                for (item in outbounds) {
                    map[item.tag] = item.urlTestDelay
                }
                _delays.value = map
                // url-test progress: count results stamped after this run
                // started. Only the manual run owns the UI state — smart
                // runs settle silently underneath. A scoped run (region-tab
                // test) settles over its own members; a full run over the
                // whole outbound snapshot.
                if (testStartEpoch > 0) {
                    val scope = kernelTestScopeTags
                    val done: Int
                    val total: Int
                    if (scope != null) {
                        done = outbounds.count { it.tag in scope && it.urlTestTime >= testStartEpoch }
                        total = scope.size
                    } else {
                        done = outbounds.count { it.urlTestTime >= testStartEpoch }
                        val prevTotal = _testProgress.value?.second ?: 0
                        total = maxOf(prevTotal, outbounds.size)
                    }
                    if (total > 0) {
                        if (done >= total) {
                            testStartEpoch = 0
                            if (kernelTestManual) {
                                _testProgress.value = null
                                _testing.value = false
                            }
                        } else if (kernelTestManual) {
                            _testProgress.value = done.coerceAtMost(total) to total
                        }
                    }
                }
                // NB: snapshots can arrive at any moment (smart rounds,
                // delayed pushes) and must never kill an in-flight run —
                // the settle branch above and the callers' finally own the
                // testing state.
            }

            override fun initializeClashMode(modeList: List<String>, currentMode: String) {
                _clashMode.value = ClashModeState(modeList, currentMode)
                normalizeRoutingMode(currentMode)?.let { _routingMode.value = it }
            }

            override fun updateClashMode(newMode: String) {
                _clashMode.value = _clashMode.value.copy(current = newMode)
                normalizeRoutingMode(newMode)?.let { _routingMode.value = it }
            }
        },
    )

    private fun normalizeRoutingMode(mode: String): String? =
        mode.lowercase().takeIf { it == "rule" || it == "global" || it == "direct" }

    // ---- mihomo live bridge (Clash REST → same state the libbox client feeds) ----

    private val clashApi by lazy { ClashApiClient(MihomoCore.API_PORT, Settings.apiSecret) }
    private var mihomoJob: Job? = null
    private var mihomoLastDown = -1L
    private var mihomoLastUp = -1L

    /** libbox group snapshot → neutral CoreGroup. */
    private fun convertGroup(group: OutboundGroup): CoreGroup {
        val iterator = group.items
        val items = mutableListOf<CoreGroupItem>()
        while (iterator.hasNext()) {
            val item = iterator.next()
            items.add(CoreGroupItem(item.tag, item.type, item.urlTestDelay, item.urlTestTime))
        }
        return CoreGroup(
            tag = group.tag,
            type = group.type,
            selected = group.selected?.takeIf { it.isNotBlank() },
            items = items,
        )
    }

    private fun startMihomoBridge() {
        if (mihomoJob?.isActive == true) return
        mihomoJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                when (Settings.coreKind) {
                    CoreKind.MIHOMO -> runCatching { pollMihomoOnce() }
                    CoreKind.XRAY -> runCatching { pollXrayOnce() }
                    CoreKind.SINGBOX -> Unit
                }
                delay(2000)
            }
        }
    }

    /**
     * Xray has no control API — the live bridge only carries the hev TUN
     * counters (traffic card) and promotes Starting → Started once the
     * inbound is up; node/delay state stays pool-driven.
     */
    private fun pollXrayOnce() {
        if (probing) return
        if (_status.value == Status.Starting) markStarted()
        if (!com.interstellar.proxy.core.TProxyService.running) return
        // hev counters: [txPackets, txBytes, rxPackets, rxBytes]
        val stats = com.interstellar.proxy.core.TProxyService.stats() ?: return
        val tx = stats.getOrElse(1) { 0L }
        val rx = stats.getOrElse(3) { 0L }
        if (mihomoLastDown >= 0 && rx >= mihomoLastDown && tx >= mihomoLastUp) {
            _speed.value = SpeedState(
                uplinkPerSecond = (tx - mihomoLastUp) / 2,
                downlinkPerSecond = (rx - mihomoLastDown) / 2,
                uplinkTotal = tx,
                downlinkTotal = rx,
            )
            _history.value =
                (_history.value + ((rx - mihomoLastDown) / 2 to (tx - mihomoLastUp) / 2)).takeLast(60)
        }
        mihomoLastDown = rx
        mihomoLastUp = tx
    }

    private suspend fun pollMihomoOnce() {
        if (clashApi.version() == null) {
            // core unreachable (dead or mid-respawn): drop the dead snapshot
            // so the nodes page falls back to groups parsed from the on-disk
            // config instead of showing the old core's last state forever
            if (_groups.value.isNotEmpty()) _groups.value = emptyList()
            return
        }
        if (probing) return
        if (_status.value == Status.Starting) markStarted()

        clashApi.proxies()?.let { proxies ->
            val delays = mutableMapOf<String, Int>()
            for ((name, v) in proxies) {
                val history = v.jsonObject["history"]?.let { h ->
                    (h as? kotlinx.serialization.json.JsonArray)?.lastOrNull()?.jsonObject
                }
                history?.get("delay")?.jsonPrimitive?.content?.toIntOrNull()?.let { delays[name] = it }
            }
            _delays.value = delays
            val groups = proxies.values.mapNotNull { entry ->
                val obj = entry.jsonObject
                val tag = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (tag in setOf("GLOBAL", "DIRECT", "REJECT", "COMPATIBLE", "PASS")) return@mapNotNull null
                val all = obj["all"] as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                if (all.isEmpty()) return@mapNotNull null
                CoreGroup(
                    tag = tag,
                    type = obj["type"]?.jsonPrimitive?.content ?: "Selector",
                    selected = obj["now"]?.jsonPrimitive?.content,
                    items = all.map { nameEl ->
                        val name = nameEl.jsonPrimitive.content
                        CoreGroupItem(name, proxies[name]?.jsonObject?.get("type")?.jsonPrimitive?.content ?: "", delays[name] ?: 0, 0L)
                    },
                )
            }
            _groups.value = groups
            refreshSplitRuleStatus()
        }

        clashApi.connections()?.let { conn ->
            (conn["connections"] as? kotlinx.serialization.json.JsonArray)?.let {
                _mihomoConnectionCount.value = it.size
            }
            val down = conn["downloadTotal"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@let
            val up = conn["uploadTotal"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@let
            if (mihomoLastDown >= 0 && down >= mihomoLastDown && up >= mihomoLastUp) {
                _speed.value = SpeedState(
                    uplinkPerSecond = (up - mihomoLastUp) / 2,
                    downlinkPerSecond = (down - mihomoLastDown) / 2,
                    uplinkTotal = up,
                    downlinkTotal = down,
                )
                _history.value = (_history.value + ((down - mihomoLastDown) / 2 to (up - mihomoLastUp) / 2)).takeLast(60)
            }
            mihomoLastDown = down
            mihomoLastUp = up
        }
    }

    init {
        ensureApiSecret()
        // the nodes page shows these until the core runs and live groups arrive
        refreshStaticGroups()
    }

    fun connect() {
        // libbox command socket only exists for the sing-box engine
        if (Settings.coreKind == CoreKind.SINGBOX) commandClient.connect()
        startMihomoBridge()
        registerStoppedReceiver()
        if (Settings.selectedOutboundTag == com.interstellar.proxy.data.config.ConfigBuilder.SMART_TAG) {
            smartEngine.start()
        }
        // poll-reconnect: the box may start/stop at any time, and a failed
        // connect (server not up yet) must be retried to reflect the state
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(2000)
                if (_status.value == Status.Starting || _status.value == Status.Stopped) {
                    if (Settings.coreKind == CoreKind.SINGBOX) commandClient.connect()
                }
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        mihomoJob?.cancel()
        mihomoJob = null
        mihomoLastDown = -1L
        mihomoLastUp = -1L
        startingWatchdog?.cancel()
        startingWatchdog = null
        unregisterStoppedReceiver()
        commandClient.disconnect()
    }

    private fun registerStoppedReceiver() {
        if (stoppedReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Action.SERVICE_STOPPED) markStopped()
            }
        }
        stoppedReceiver = receiver
        ContextCompat.registerReceiver(
            getApplication(),
            receiver,
            IntentFilter(Action.SERVICE_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregisterStoppedReceiver() {
        val receiver = stoppedReceiver ?: return
        stoppedReceiver = null
        runCatching { getApplication<Application>().unregisterReceiver(receiver) }
    }

    private fun ensureApiSecret() {
        if (Settings.apiSecret.isBlank()) {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            Settings.apiSecret = android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
            )
        }
    }

    private fun markStarted() {
        startingWatchdog?.cancel()
        startingWatchdog = null
        if (_status.value == Status.Started) return
        _status.value = Status.Started
        _connectedAt.value = System.currentTimeMillis()
        _probe.value = ProbeState.Idle
        Settings.tileActive = true
        if (!autoTested) {
            autoTested = true
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { CommandTarget.standaloneClient().urlTest(ConfigBuilder.AUTO_TAG) }
            }
            // once the url-test settles, refresh the exit-IP card
            viewModelScope.launch {
                delay(6_000)
                if (_status.value == Status.Started && _probe.value == ProbeState.Idle) {
                    probeNetwork()
                }
            }
        }
    }

    private fun markStopped() {
        startingWatchdog?.cancel()
        startingWatchdog = null
        autoTested = false
        if (_status.value != Status.Stopped) {
            _status.value = Status.Stopped
        }
        _connectedAt.value = 0L
        Settings.tileActive = false
    }

    private fun armStartingWatchdog(timeoutMs: Long) {
        startingWatchdog?.cancel()
        startingWatchdog = viewModelScope.launch {
            delay(timeoutMs)
            if (_status.value == Status.Starting || _status.value == Status.Started) {
                markStopped()
                com.interstellar.proxy.bg.BoxService.stop()
            }
        }
    }

    /**
     * Command socket drops during core start/reload. Stay on the current
     * face until SERVICE_STOPPED arrives, or a short grace expires.
     * Do not flip Started → Starting — that is the "连接中" hang.
     */
    private fun applySocketDrop() {
        // connection errors from the (sing-box only) command client must not
        // tear down a healthy sidecar engine
        if (Settings.coreKind != CoreKind.SINGBOX) return
        when (_status.value) {
            Status.Stopping -> markStopped()
            Status.Started -> armStartingWatchdog(SOCKET_DROP_TIMEOUT_MS)
            else -> Unit
        }
    }

    fun startProxy() {
        android.util.Log.d("InterstellarUI", "startProxy invoked")
        if (probing) {
            probing = false
            com.interstellar.proxy.bg.BoxService.stop()
        }
        _status.value = Status.Starting
        armStartingWatchdog(STARTING_TIMEOUT_MS)
        _message.value = null
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                delay(250)
                android.util.Log.d("InterstellarUI", "activeSub=" + (SubscriptionRepository.activeSubscription()?.name ?: "null"))
                if (SubscriptionRepository.subscriptions.isEmpty()) {
                    _message.value = str(com.interstellar.proxy.R.string.vm_need_subscription)
                    markStopped()
                    return@launch
                }
                val config = SubscriptionRepository.regenerateActiveConfig()
                if (config == null) {
                    _message.value = SubscriptionRepository.lastConfigError
                        ?: str(com.interstellar.proxy.R.string.vm_config_gen_failed_no_nodes)
                    markStopped()
                    return@launch
                }
                android.util.Log.d("InterstellarUI", "config ok length=" + config.length)
                com.interstellar.proxy.bg.BoxService.start()
                commandClient.connect()
            } catch (e: Exception) {
                _message.value = str(com.interstellar.proxy.R.string.vm_start_failed, e.message ?: "")
                markStopped()
            } finally {
                _busy.value = false
            }
        }
    }

    fun stopProxy() {
        startingWatchdog?.cancel()
        startingWatchdog = null
        _status.value = Status.Stopping
        com.interstellar.proxy.bg.BoxService.stop()
    }

    /**
     * Regenerates the active config from current settings (bypass-LAN,
     * bypass-CN, ad-block, mode…) and hot-reloads the running core.
     */
    fun refreshProxyConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            val config = SubscriptionRepository.regenerateActiveConfig()
            if (config == null) {
                _message.value = SubscriptionRepository.lastConfigError
                    ?: str(com.interstellar.proxy.R.string.vm_config_update_failed)
                return@launch
            }
            _staticGroups.value = parseStaticGroups(config, Settings.coreKind)
            // a switch during Starting would otherwise be silently dropped: the
            // in-flight start already consumed the previous config and no
            // reload fires — wait for the start to settle, then apply
            if (_status.value == Status.Starting) {
                var waited = 0
                while (_status.value == Status.Starting && waited < 20_000) {
                    delay(200)
                    waited += 200
                }
            }
            if (_status.value == Status.Started) {
                when (Settings.coreKind) {
                    CoreKind.MIHOMO -> runCatching {
                        com.interstellar.proxy.core.MihomoCore.Holder.instance?.refreshFromConfigStore()
                    }

                    CoreKind.XRAY -> runCatching {
                        com.interstellar.proxy.core.XrayCore.Holder.instance?.restartFromConfigStore()
                    }

                    CoreKind.SINGBOX -> runCatching { CommandTarget.standaloneClient().serviceReload() }
                }
            }
        }
    }

    /** Re-reads whatever config is on disk (startup / core switch). */
    fun refreshStaticGroups() {
        viewModelScope.launch(Dispatchers.IO) {
            val content = com.interstellar.proxy.data.ConfigStore.readActiveConfig()
            _staticGroups.value =
                content?.let { parseStaticGroups(it, Settings.coreKind) } ?: emptyList()
        }
    }

    /**
     * Groups out of a generated/raw config: mihomo proxy-groups or sing-box
     * selector/urltest outbounds. Xray has no group concept — callers fall
     * back to the stored node pool.
     */
    private fun parseStaticGroups(content: String, coreKind: CoreKind): List<CoreGroup> =
        runCatching {
            when (coreKind) {
                CoreKind.MIHOMO -> parseClashGroups(content)
                CoreKind.SINGBOX -> parseSingboxGroups(content)
                CoreKind.XRAY -> emptyList()
            }
        }.getOrDefault(emptyList())

    private fun parseClashGroups(yaml: String): List<CoreGroup> {
        val json = com.interstellar.proxy.data.subscription.YamlToJson.convert(yaml) ?: return emptyList()
        val groups = json.arr("proxy-groups") ?: return emptyList()
        // normalize clash yaml type spellings onto the live-API forms, so a
        // stopped raw config classifies groups exactly like a running core
        // (isGroupItem / ⚡ tabs / member filtering all key off these)
        fun normType(raw: String) = when (raw.lowercase()) {
            "select" -> "selector"
            "url-test" -> "urltest"
            "load-balance" -> "loadbalance"
            else -> raw.lowercase()
        }
        // members that reference another group get that group's type, so the
        // nodes page can render them as group cards while the core is stopped
        val typeByName = groups.mapNotNull { el ->
            (el as? kotlinx.serialization.json.JsonObject)?.let { g ->
                g.str("name")?.let { it to normType(g.str("type") ?: "select") }
            }
        }.toMap()
        return groups.mapNotNull { el ->
            val g = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val name = g.str("name") ?: return@mapNotNull null
            CoreGroup(
                tag = name,
                type = normType(g.str("type") ?: "select"),
                // runtime state unknown while stopped — highlight nothing
                selected = null,
                items = (g.strList("proxies") ?: emptyList()).map {
                    CoreGroupItem(it, typeByName[it] ?: "")
                },
            )
        }
    }

    private fun parseSingboxGroups(content: String): List<CoreGroup> {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(content)
            .let { it as? kotlinx.serialization.json.JsonObject } ?: return emptyList()
        val outbounds = (root["outbounds"] as? kotlinx.serialization.json.JsonArray)
            ?.filterIsInstance<kotlinx.serialization.json.JsonObject>() ?: return emptyList()
        val typeOf = outbounds.associateBy { it.str("tag") }
        return outbounds.mapNotNull { ob ->
            val type = ob.str("type")?.lowercase() ?: return@mapNotNull null
            if (type != "selector" && type != "urltest") return@mapNotNull null
            val tag = ob.str("tag") ?: return@mapNotNull null
            val members = (ob["outbounds"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: emptyList()
            CoreGroup(
                tag = tag,
                type = type,
                selected = ob.str("default")?.takeIf { it in members },
                items = members.map { CoreGroupItem(it, typeOf[it]?.str("type") ?: "") },
            )
        }
    }

    /**
     * Downloads the latest rule/geodata files for the active core, then
     * reloads so the fresh files take effect immediately: sing-box re-reads
     * local rule-sets on service reload; sidecars get a full respawn
     * (mihomo/xray only load geodata at process spawn).
     */
    fun updateRuleFiles() {
        if (_ruleFilesUpdating.value) return
        _ruleFilesUpdating.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val summary = com.interstellar.proxy.data.net.GeoRuleUpdater.update(Settings.coreKind)
                _message.value = summary
                val config = SubscriptionRepository.regenerateActiveConfig()
                if (config != null && _status.value == Status.Started) {
                    when (Settings.coreKind) {
                        CoreKind.MIHOMO -> runCatching {
                            com.interstellar.proxy.core.MihomoCore.Holder.instance?.restartFromConfigStore()
                        }

                        CoreKind.XRAY -> runCatching {
                            com.interstellar.proxy.core.XrayCore.Holder.instance?.restartFromConfigStore()
                        }

                        CoreKind.SINGBOX -> runCatching { CommandTarget.standaloneClient().serviceReload() }
                    }
                }
            } catch (e: Exception) {
                _message.value = str(com.interstellar.proxy.R.string.vm_rule_update_failed, e.message ?: "")
            } finally {
                _ruleFilesUpdating.value = false
            }
        }
    }

    /**
     * Switch the active engine: stop the running service (an in-flight core
     * can't morph into another), regenerate the config for the new core and
     * start it again.
     */
    fun switchCore(kind: CoreKind) {
        if (Settings.coreKind == kind) return
        viewModelScope.launch(Dispatchers.IO) {
            val wasRunning = _status.value == Status.Started || _status.value == Status.Starting
            if (wasRunning) {
                com.interstellar.proxy.bg.BoxService.stop()
                var waited = 0
                while (_status.value != Status.Stopped && waited < 10_000) {
                    delay(200)
                    waited += 200
                }
            }
            com.interstellar.proxy.core.AppLog.log("core", "切换内核 → ${kind.displayName}")
            smartEngine.stop() // probe client type follows the new core
            Settings.coreKind = kind
            _coreKind.value = kind
            _groups.value = emptyList()
            _delays.value = emptyMap()
            mihomoLastDown = -1L
            mihomoLastUp = -1L
            val config = SubscriptionRepository.regenerateActiveConfig()
            if (config == null) {
                _message.value = SubscriptionRepository.lastConfigError
                    ?: str(com.interstellar.proxy.R.string.vm_config_update_failed)
                return@launch
            }
            _staticGroups.value = parseStaticGroups(config, kind)
            if (wasRunning) {
                com.interstellar.proxy.bg.BoxService.start()
                if (Settings.selectedOutboundTag == com.interstellar.proxy.data.config.ConfigBuilder.SMART_TAG) {
                    smartEngine.start()
                }
            }
        }
    }

    fun setClashMode(mode: String) {
        val normalized = normalizeRoutingMode(mode) ?: return
        // optimistic: the seg moves immediately, the reload below confirms it
        _routingMode.value = normalized
        viewModelScope.launch(Dispatchers.IO) {
            // keep the core's clash API state in sync (the actual routing is
            // baked into the regenerated config below)
            runCatching {
                CommandTarget.standaloneClient().setClashMode(normalized)
            }
            Settings.outboundMode = when (normalized) {
                "global" -> ConfigBuilder.OutboundMode.GLOBAL
                "direct" -> ConfigBuilder.OutboundMode.DIRECT
                else -> ConfigBuilder.OutboundMode.RULE
            }
            refreshSplitRuleStatus()
            // regenerate with the new mode, then hot-reload so a running core
            // picks up the new route.final / CN bypass rules immediately
            val config = SubscriptionRepository.regenerateActiveConfig()
            if (config != null && _status.value == Status.Started) {
                runCatching { CommandTarget.standaloneClient().serviceReload() }
            }
        }
    }

    fun selectNode(groupTag: String, itemTag: String) {
        Settings.selectedOutboundTag = itemTag
        _selectedOutboundTag.value = itemTag
        refreshSplitRuleStatus()
        // picking a concrete node exits smart mode
        if (itemTag != com.interstellar.proxy.data.config.ConfigBuilder.SMART_TAG) {
            smartEngine.stop()
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (_status.value == Status.Started) {
                when (Settings.coreKind) {
                    CoreKind.MIHOMO -> {
                        val ok = runCatching { clashApi.select(groupTag, itemTag) }.getOrDefault(false)
                        if (!ok) _message.value = str(com.interstellar.proxy.R.string.vm_switch_failed_api)
                        runCatching { pollMihomoOnce() }
                    }

                    CoreKind.XRAY -> {
                        // Xray has no selector — regenerate + respawn ("重启生效")
                        SubscriptionRepository.regenerateActiveConfig()
                        runCatching {
                            com.interstellar.proxy.core.XrayCore.Holder.instance?.restartFromConfigStore()
                        }.onSuccess {
                            _message.value = str(com.interstellar.proxy.R.string.vm_xray_restarted)
                        }
                    }

                    CoreKind.SINGBOX -> runCatching {
                        CommandTarget.standaloneClient().selectOutbound(groupTag, itemTag)
                    }.onFailure {
                        _message.value = str(com.interstellar.proxy.R.string.vm_switch_failed, it.message ?: "")
                    }
                }
            } else {
                // stopped: bake the pick into the regenerated config and
                // refresh the static groups so the page reflects it at once
                val config = SubscriptionRepository.regenerateActiveConfig()
                if (config != null) {
                    _staticGroups.value = parseStaticGroups(config, Settings.coreKind)
                }
                _selectedOutboundTag.value = Settings.selectedOutboundTag
            }
        }
    }

    /**
     * A node tap inside a group tab whose nodes the main selector doesn't
     * carry (raw airport configs): pin the node inside the group's own
     * selector, then route the main selector through the group — traffic
     * rides the tapped node and the stored selection lands on the group.
     */
    fun selectNodeViaGroup(groupTag: String, nodeTag: String) {
        val mainTag = wholePoolMainSelector() ?: ConfigBuilder.GROUP_TAG
        Settings.selectedOutboundTag = groupTag
        _selectedOutboundTag.value = groupTag
        refreshSplitRuleStatus()
        // a concrete pick always exits smart mode
        smartEngine.stop()
        viewModelScope.launch(Dispatchers.IO) {
            if (_status.value == Status.Started) {
                when (Settings.coreKind) {
                    CoreKind.MIHOMO -> {
                        val okGroup = runCatching { clashApi.select(groupTag, nodeTag) }.getOrDefault(false)
                        val okMain = runCatching { clashApi.select(mainTag, groupTag) }.getOrDefault(false)
                        if (!okGroup && !okMain) {
                            _message.value = str(com.interstellar.proxy.R.string.vm_switch_failed_api)
                        }
                        runCatching { pollMihomoOnce() }
                    }

                    CoreKind.SINGBOX -> runCatching {
                        CommandTarget.standaloneClient().selectOutbound(groupTag, nodeTag)
                        CommandTarget.standaloneClient().selectOutbound(mainTag, groupTag)
                    }.onFailure {
                        _message.value = str(com.interstellar.proxy.R.string.vm_switch_failed, it.message ?: "")
                    }

                    // Xray has no groups — the tab never exists under Xray
                    CoreKind.XRAY -> Unit
                }
            } else {
                // stopped: bake main→group into the regenerated config and
                // refresh the static groups so the page reflects it at once
                val config = SubscriptionRepository.regenerateActiveConfig()
                if (config != null) {
                    _staticGroups.value = parseStaticGroups(config, Settings.coreKind)
                }
                _selectedOutboundTag.value = Settings.selectedOutboundTag
            }
        }
    }

    /** The main selector as the configs name it (generated tag, else first selector). */
    private fun wholePoolMainSelector(): String? =
        _staticGroups.value.find { it.tag == ConfigBuilder.GROUP_TAG }?.tag
            ?: _groups.value.find { it.tag == ConfigBuilder.GROUP_TAG }?.tag
            ?: _staticGroups.value.firstOrNull { it.type.equals("selector", ignoreCase = true) }?.tag
            ?: _groups.value.firstOrNull { it.type.equals("selector", ignoreCase = true) }?.tag

    fun setSplitRulesEnabled(enabled: Boolean) {
        Settings.splitRulesEnabled = enabled
        refreshSplitRuleStatus()
        refreshProxyConfig()
    }

    fun upsertCustomRule(rule: CustomRouteRule) {
        CustomRulesStore.upsert(rule)
        _customRules.value = CustomRulesStore.rules.toList()
        refreshSplitRuleStatus()
        refreshProxyConfig()
    }

    fun removeCustomRule(id: String) {
        CustomRulesStore.remove(id)
        _customRules.value = CustomRulesStore.rules.toList()
        refreshSplitRuleStatus()
        refreshProxyConfig()
    }

    fun setCustomRuleEnabled(id: String, enabled: Boolean) {
        CustomRulesStore.setEnabled(id, enabled)
        _customRules.value = CustomRulesStore.rules.toList()
        refreshSplitRuleStatus()
        refreshProxyConfig()
    }

    fun upsertDnsOverride(entry: DnsOverrideEntry) {
        DnsOverridesStore.upsert(entry)
        _dnsOverrides.value = DnsOverridesStore.entries.toList()
        refreshProxyConfig()
    }

    fun removeDnsOverride(id: String) {
        DnsOverridesStore.remove(id)
        _dnsOverrides.value = DnsOverridesStore.entries.toList()
        refreshProxyConfig()
    }

    fun setDnsOverrideEnabled(id: String, enabled: Boolean) {
        DnsOverridesStore.setEnabled(id, enabled)
        _dnsOverrides.value = DnsOverridesStore.entries.toList()
        refreshProxyConfig()
    }

    private fun refreshSplitRuleStatus() {
        _splitRuleStatus.value = computeSplitRuleStatus()
    }

    private fun computeSplitRuleStatus(): SplitRuleStatus {
        val has = CustomRulesStore.rules.any { it.enabled }
        val master = Settings.splitRulesEnabled
        val clashRule = Settings.outboundMode == ConfigBuilder.OutboundMode.RULE
        return SplitRuleStatus(
            hasEnabledRules = has,
            masterEnabled = master,
            active = has && master && clashRule,
        )
    }

    /**
     * Batch url-test. [groupTag] names the kernel-side group to test — a
     * region tab passes its group so only that group's nodes run; null (or
     * the main selector) tests the whole pool via [wholePoolTestTag].
     * [scopeTags] limits progress/summary/ping accounting to the given
     * member tags; derived from the group when omitted.
     */
    fun urlTest(groupTag: String?, scopeTags: List<String>? = null) {
        if (_testing.value) return
        _testing.value = true
        val scope = scopeTags?.takeIf { it.isNotEmpty() }
            ?: groupTag?.let { groupMemberTags(it) }?.takeIf { it.isNotEmpty() }
        _testProgress.value = 0 to urlTestTotal(scope)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val kernelTag = groupTag ?: wholePoolTestTag()
                if (_status.value == Status.Started) {
                    when (Settings.coreKind) {
                        // app-driven per-node probes with bounded concurrency
                        // — the kernel's batch endpoint fires every member at
                        // once, the herd saturates the radio and inflates the
                        // numbers (fast nodes read hundreds of ms). Waves of
                        // 12 keep the values honest and stream in one by one.
                        // The libbox command + stream settle stays as the
                        // sing-box fallback when the REST API is unreachable.
                        CoreKind.MIHOMO, CoreKind.SINGBOX -> {
                            val members = restTestMembers(kernelTag)
                            if (!members.isNullOrEmpty()) {
                                restUrlTestPerNode(members)
                            } else if (Settings.coreKind == CoreKind.SINGBOX &&
                                runKernelUrlTest(manual = true, groupTag = kernelTag, scopeTags = scope)
                            ) {
                                // settled through the outbounds stream
                            } else {
                                _message.value = str(com.interstellar.proxy.R.string.vm_test_failed_api)
                            }
                        }

                        // no API — TCP ping the pool (or the scoped slice) directly
                        CoreKind.XRAY -> tcpPingPool(scope)
                    }
                } else {
                    runDisconnectedUrlTest(kernelTag, scope)
                }
            } catch (e: Exception) {
                _message.value = str(com.interstellar.proxy.R.string.vm_test_failed, e.message ?: "")
            } finally {
                _testing.value = false
                _testProgress.value = null
                testStartEpoch = 0
            }
        }
    }

    private fun applyDelays(result: Map<String, Int>) {
        _delays.value = _delays.value.toMutableMap().also { map ->
            result.forEach { (name, delay) -> map[name] = delay }
        }
    }

    /**
     * Node members of [groupTag] the REST surface can probe individually —
     * group-type members (selector/urltest/fallback/…) are skipped, they
     * aren't real outbounds. Null = REST API unreachable or the group
     * unknown (caller falls back to the kernel path).
     */
    private suspend fun restTestMembers(groupTag: String): List<String>? {
        if (clashApi.version() == null) return null
        val items = _groups.value.find { it.tag == groupTag }?.items
            ?: _staticGroups.value.find { it.tag == groupTag }?.items
            ?: return null
        val groupTypes = setOf("selector", "urltest", "fallback", "loadbalance", "relay")
        return items.map { it.tag to it.type.lowercase() }
            .filter { (_, type) -> type !in groupTypes }
            .map { (tag, _) -> tag }
    }

    /**
     * Per-node /proxies/{name}/delay probes in waves of 12: the kernel's
     * own batch endpoint launches every member simultaneously and the
     * thundering herd inflates each measurement (a 100ms node reads
     * 700ms); bounded waves keep the numbers honest and each result
     * streams into [delays] the moment it lands.
     */
    private suspend fun restUrlTestPerNode(tags: List<String>) {
        val total = tags.size
        val done = java.util.concurrent.atomic.AtomicInteger()
        val semaphore = kotlinx.coroutines.sync.Semaphore(12)
        kotlinx.coroutines.coroutineScope {
            tags.forEach { tag ->
                launch {
                    semaphore.withPermit {
                        val delayMs = clashApi.proxyDelay(tag)
                        delaysMutex.withLock {
                            _delays.value = _delays.value.toMutableMap().also { map ->
                                map[tag] = delayMs ?: TIMEOUT_DELAY
                            }
                        }
                        _testProgress.value = done.incrementAndGet() to total
                    }
                }
            }
        }
    }

    /**
     * Kernel-side tag a whole-pool run should test: AUTO when the config
     * carries our urltest-all group (its flat member list IS every node),
     * else the main selector itself — raw configs name theirs anything, and
     * a partial pass over it beats a command against a group that doesn't
     * exist.
     */
    private fun wholePoolTestTag(): String {
        val live = _groups.value
        val static = _staticGroups.value
        if (live.any { it.tag == ConfigBuilder.AUTO_TAG } || static.any { it.tag == ConfigBuilder.AUTO_TAG }) {
            return ConfigBuilder.AUTO_TAG
        }
        return static.find { it.tag == ConfigBuilder.GROUP_TAG }?.tag
            ?: live.find { it.tag == ConfigBuilder.GROUP_TAG }?.tag
            ?: static.firstOrNull { it.type.equals("selector", ignoreCase = true) }?.tag
            ?: live.firstOrNull { it.type.equals("selector", ignoreCase = true) }?.tag
            ?: ConfigBuilder.AUTO_TAG
    }

    /** Members of a group as the live core sees it, falling back to the static parse. */
    private fun groupMemberTags(tag: String): List<String>? =
        _groups.value.find { it.tag == tag }?.items?.map { it.tag }
            ?: _staticGroups.value.find { it.tag == tag }?.items?.map { it.tag }

    /** Denominator for the batch progress: scope → live group size → known delays → pool size. */
    private fun urlTestTotal(scope: List<String>?): Int {
        scope?.takeIf { it.isNotEmpty() }?.let { return it.size }
        _groups.value.find { it.tag == GROUP_TAG }?.let { group ->
            if (group.items.isNotEmpty()) return group.items.size
        }
        if (_delays.value.isNotEmpty()) return _delays.value.size
        return SubscriptionRepository.poolOf(
            _subscriptions.value,
            _activeSubscriptionId.value,
            _mixEnabled.value,
            _mixSubscriptionIds.value,
        ).size
    }

    /**
     * Wait for the kernel url-test to finish. Nodes test concurrently, each
     * with sing-box's own per-node HTTP timeout (constant.TCPTimeout = 15s,
     * not configurable), so the run is bounded by that plus scheduling
     * margin — wait for the stream's done>=total clear, capped at 15s+10s.
     * No independent overall-stall heuristic.
     */
    /**
     * One kernel url-test run over the AUTO group, serialized between the
     * manual button and the smart engine: both send the same command, and
     * without the mutex one run's stream pushes would settle the other's
     * wait (or let a stray push kill the manual spinner mid-run). Marks the
     * run epoch, sends the command, then waits for the outbounds stream to
     * report every member stamped after the epoch — bounded by the kernel's
     * per-node timeout plus margin. False = the command could not be sent.
     * Fallback path only — the clash REST batch is the primary route.
     */
    private suspend fun runKernelUrlTest(
        manual: Boolean,
        groupTag: String = ConfigBuilder.AUTO_TAG,
        scopeTags: List<String>? = null,
    ): Boolean =
        kernelUrlTestMutex.withLock {
            kernelTestManual = manual
            kernelTestScopeTags = scopeTags?.toSet()
            testStartEpoch = System.currentTimeMillis() / 1000
            if (manual) {
                // a colliding run's settle may have flashed these off while
                // this caller was waiting on the mutex
                _testing.value = true
                _testProgress.value = 0 to (scopeTags?.takeIf { it.isNotEmpty() }?.size ?: urlTestTotal(null))
            }
            val ok = runCatching {
                CommandTarget.standaloneClient().urlTest(groupTag)
            }.isSuccess
            if (!ok) {
                testStartEpoch = 0
                kernelTestScopeTags = null
                return@withLock false
            }
            val deadline = System.currentTimeMillis() + PER_NODE_TEST_TIMEOUT_MS + 10_000
            while (testStartEpoch > 0 && System.currentTimeMillis() < deadline) delay(500)
            testStartEpoch = 0 // cap fallback; callers' finally owns the UI state
            true
        }

    /**
     * Spin up the core without TUN so url-test can run while the UI stays
     * 未连接 — scoped to [groupTag]'s members when a region tab asked.
     * Restores the previous config afterwards.
     */
    private suspend fun runDisconnectedUrlTest(groupTag: String, scope: List<String>?) {
        // Xray delay = direct TCP pings; spinning a headless core buys nothing
        if (Settings.coreKind == CoreKind.XRAY) {
            tcpPingPool(scope)
            return
        }
        probing = true
        probeSocketUp = false
        val previous = ConfigStore.readActiveConfig()
        try {
            SubscriptionRepository.regenerateActiveConfig(includeTun = false)
                ?: throw IllegalStateException(
                    SubscriptionRepository.lastConfigError
                        ?: str(com.interstellar.proxy.R.string.vm_no_test_config),
                )
            com.interstellar.proxy.bg.BoxService.startHeadless()
            // both kernels' configs force clash_api on the fixed port — wait
            // for the REST surface, then one bounded batch call settles the
            // whole run. No command socket, no stream-settle deadline.
            var connected = false
            for (i in 0 until 150) { // 150 × 200ms = 30s budget
                delay(200)
                if (_status.value == Status.Starting || _status.value == Status.Started) return
                if (clashApi.version() != null) {
                    connected = true
                    break
                }
            }
            if (!connected) throw IllegalStateException(str(com.interstellar.proxy.R.string.vm_test_service_timeout))
            // per-node waves (honest numbers, streaming results); one server
            // batch as fallback for group shapes without bare node members
            val members = restTestMembers(groupTag)
            if (!members.isNullOrEmpty()) {
                restUrlTestPerNode(members)
            } else {
                val result = clashApi.groupDelay(groupTag)
                    ?: throw IllegalStateException(str(com.interstellar.proxy.R.string.vm_test_cmd_failed))
                applyDelays(result)
            }
        } finally {
            probeSocketUp = false
            val takenOver = _status.value == Status.Starting || _status.value == Status.Started
            if (!takenOver) {
                com.interstellar.proxy.bg.BoxService.stop()
                delay(250)
                if (previous != null) ConfigStore.writeActiveConfig(previous)
                else SubscriptionRepository.regenerateActiveConfig()
            }
            probing = false
        }
    }

    /** Aggregated per-run ping outcome for the summary bar. */
    data class PingReport(
        val failed: Int,
        val skippedUdp: Int,
        val reasons: Map<String, Int>,
    )

    @Volatile
    var lastPingReport: PingReport? = null
        private set

    /**
     * Concurrent direct TCP ping over the current pool (satelite-style):
     * no core required, results stream into [delays] one by one so a
     * delay-sorted list re-orders immediately. Failed connects are marked
     * with the TIMEOUT sentinel and classified into [lastPingReport].
     * UDP-only protocols (hysteria2/tuic/wireguard/quic) can't be TCP-pinged
     * and are skipped with a note. Sockets bypass our own tun via
     * [DirectPing] — a plain connect would handshake with the local tun
     * stack (~3-4ms) while the VPN is up. [scopeTags] narrows the sweep to
     * a group tab's members (null = whole pool).
     */
    fun tcpPingPool(scopeTags: List<String>? = null) {
        if (_pinging.value) return
        _pinging.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // while connected, the tracker decides between a real
                // physical-network ping and a meaningless tun-loop ping
                DirectPing.warmup(if (_status.value == Status.Started) 1_500 else 0)
                val pool = SubscriptionRepository.poolOf(
                    _subscriptions.value,
                    _activeSubscriptionId.value,
                    _mixEnabled.value,
                    _mixSubscriptionIds.value,
                )
                if (pool.isEmpty()) {
                    showToast(str(com.interstellar.proxy.R.string.vm_no_nodes_to_test), UiToast.Kind.Error)
                    return@launch
                }
                val scope = scopeTags?.takeIf { it.isNotEmpty() }?.toSet()
                val members = pool.zip(ConfigBuilder.tagsFor(pool))
                    .filter { (_, tag) -> scope == null || tag in scope }
                if (members.isEmpty()) {
                    showToast(str(com.interstellar.proxy.R.string.vm_no_nodes_to_test), UiToast.Kind.Error)
                    return@launch
                }
                val udpOnly = setOf(
                    com.interstellar.proxy.data.model.NodeType.HYSTERIA2,
                    com.interstellar.proxy.data.model.NodeType.TUIC,
                    com.interstellar.proxy.data.model.NodeType.WIREGUARD,
                )
                val pairs = members.filter { (node, _) -> node.type !in udpOnly && node.network != "quic" }
                val skippedUdp = members.size - pairs.size
                if (pairs.isEmpty()) {
                    lastPingReport = PingReport(0, skippedUdp, emptyMap())
                    showToast(str(com.interstellar.proxy.R.string.vm_all_udp), UiToast.Kind.Error)
                    return@launch
                }
                _pingProgress.value = 0 to pairs.size
                val done = java.util.concurrent.atomic.AtomicInteger()
                val failed = java.util.concurrent.atomic.AtomicInteger()
                val reasons = java.util.concurrent.ConcurrentHashMap<String, Int>()
                val semaphore = kotlinx.coroutines.sync.Semaphore(12)
                kotlinx.coroutines.coroutineScope {
                    pairs.forEach { (node, tag) ->
                        launch {
                            semaphore.withPermit {
                                val outcome = runCatching {
                                    DirectPing.tcpConnect(node.server, node.port, 3000)
                                }
                                val value = when {
                                    outcome.exceptionOrNull() != null -> TIMEOUT_DELAY
                                    else -> outcome.getOrDefault(0).coerceAtLeast(1)
                                }
                                if (value == TIMEOUT_DELAY) {
                                    failed.incrementAndGet()
                                    val reason = when (val e = outcome.exceptionOrNull()) {
                                        null -> str(com.interstellar.proxy.R.string.vm_ping_timeout)
                                        is java.net.SocketTimeoutException -> str(com.interstellar.proxy.R.string.vm_ping_timeout)
                                        is java.net.ConnectException -> str(com.interstellar.proxy.R.string.vm_ping_refused)
                                        is java.net.UnknownHostException -> str(com.interstellar.proxy.R.string.vm_ping_dns_failed)
                                        else -> e.message?.take(18)?.takeIf { it.isNotBlank() }
                                            ?: e.javaClass.simpleName
                                    }
                                    reasons.merge(reason, 1, Int::plus)
                                }
                                delaysMutex.withLock {
                                    _delays.value = _delays.value.toMutableMap().also { it[tag] = value }
                                }
                                _pingProgress.value = done.incrementAndGet() to pairs.size
                            }
                        }
                    }
                }
                lastPingReport = PingReport(failed.get(), skippedUdp, reasons.toMap())
            } finally {
                _pinging.value = false
                _pingProgress.value = null
            }
        }
    }

    fun addSubscriptionFromUrl(name: String, url: String) {
        addSubJob = viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            _addingSub.value = true
            _addSubError.value = null
            try {
                val result = SubscriptionFetcher.fetch(url)
                importContent(
                    name.ifBlank { result.suggestedName ?: urlHost(url) },
                    url,
                    result.body,
                    result,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = str(com.interstellar.proxy.R.string.vm_download_failed, e.message ?: "")
                _message.value = msg
                _addSubError.value = msg
            } finally {
                addSubJob = null
                _busy.value = false
                _addingSub.value = false
            }
        }
    }

    fun addSubscriptionFromText(name: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _addingSub.value = true
            _addSubError.value = null
            try {
                importContent(name.ifBlank { str(com.interstellar.proxy.R.string.vm_local_subscription) }, null, text, null)
            } finally {
                _addingSub.value = false
            }
        }
    }

    private fun importContent(
        name: String,
        url: String?,
        body: String,
        traffic: SubscriptionFetcher.FetchResult?,
    ) {
        // stamp the subscription id on nodes up front so mix source labels work
        val subId = SubscriptionRepository.newSubscriptionId()
        val format = SubscriptionRepository.saveRawBody(subId, body)
        when (val parsed = SubscriptionParser.parse(body, subId)) {
            is SubscriptionParser.Result.Nodes -> {
                val sub = SubscriptionRepository.Subscription(
                    id = subId,
                    name = name,
                    url = url,
                    nodes = parsed.nodes,
                    uploadBytes = traffic?.uploadBytes ?: 0,
                    downloadBytes = traffic?.downloadBytes ?: 0,
                    totalBytes = traffic?.totalBytes ?: 0,
                    expireSeconds = traffic?.expireSeconds ?: 0,
                    lastUpdated = System.currentTimeMillis(),
                    configFormat = format,
                )
                SubscriptionRepository.upsert(sub)
                if (Settings.selectedOutboundTag.isBlank()) {
                    Settings.selectedOutboundTag = com.interstellar.proxy.data.config.ConfigBuilder.AUTO_TAG
                }
                // NOT auto-checked into the mix pool: the user ticks it in the
                // subscription list (toggleMixSubscription → applyPoolChange
                // regenerates and the node list syncs from the state flow)
                _message.value = str(com.interstellar.proxy.R.string.vm_imported_nodes, parsed.nodes.size)
            }

            SubscriptionParser.Result.Empty -> {
                // full Xray configs carry no extractable nodes but are valid raw
                // bodies — import them as raw-only subscriptions
                if (format != null) {
                    SubscriptionRepository.upsert(
                        SubscriptionRepository.Subscription(
                            id = subId,
                            name = name,
                            url = url,
                            lastUpdated = System.currentTimeMillis(),
                            configFormat = format,
                        ),
                    )
                    _message.value = str(
                        com.interstellar.proxy.R.string.vm_imported_raw,
                        name,
                        com.interstellar.proxy.data.subscription.RawConfigFormat.from(format)?.label ?: "",
                    )
                } else {
                    SubscriptionRepository.rawFileOf(subId).delete()
                    _message.value = str(com.interstellar.proxy.R.string.vm_unrecognized_content)
                    _addSubError.value = str(com.interstellar.proxy.R.string.vm_unrecognized_content)
                }
            }
        }
        _subscriptions.value = SubscriptionRepository.subscriptions.toList()
        _activeSubscriptionId.value = SubscriptionRepository.activeSubscriptionId
        _mixSubscriptionIds.value = Settings.mixSubscriptionIds
    }

    fun refreshSubscription(id: String, fromPull: Boolean = false) {
        val sub = SubscriptionRepository.get(id) ?: return
        val url = sub.url ?: run {
            showToast(str(com.interstellar.proxy.R.string.vm_local_no_refresh), UiToast.Kind.Error)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (fromPull) _refreshing.value = true
            _busy.value = true
            try {
                val result = SubscriptionFetcher.fetch(url)
                val format = SubscriptionRepository.saveRawBody(id, result.body)
                when (val parsed = SubscriptionParser.parse(result.body, id)) {
                    is SubscriptionParser.Result.Nodes -> {
                        SubscriptionRepository.upsert(
                            sub.copy(
                                nodes = parsed.nodes,
                                configFormat = format,
                                uploadBytes = result.uploadBytes,
                                downloadBytes = result.downloadBytes,
                                totalBytes = result.totalBytes,
                                expireSeconds = result.expireSeconds,
                                lastUpdated = System.currentTimeMillis(),
                            ),
                        )
                        showToast(str(com.interstellar.proxy.R.string.vm_refreshed, sub.name, parsed.nodes.size), UiToast.Kind.Success)
                    }

                    SubscriptionParser.Result.Empty ->
                        if (format != null) {
                            SubscriptionRepository.upsert(
                                sub.copy(configFormat = format, lastUpdated = System.currentTimeMillis()),
                            )
                            showToast(str(com.interstellar.proxy.R.string.vm_config_kept, sub.name), UiToast.Kind.Success)
                        } else {
                            showToast(str(com.interstellar.proxy.R.string.vm_refresh_unparsable, sub.name), UiToast.Kind.Error)
                        }
                }
            } catch (e: Exception) {
                showToast(str(com.interstellar.proxy.R.string.vm_refresh_failed, sub.name, e.message ?: ""), UiToast.Kind.Error)
            } finally {
                _busy.value = false
                _refreshing.value = false
                // upsert regenerated the active config behind refreshProxyConfig's back
                refreshStaticGroups()
                _subscriptions.value = SubscriptionRepository.subscriptions.toList()
                _activeSubscriptionId.value = SubscriptionRepository.activeSubscriptionId
            }
        }
    }

    /** Refresh every URL subscription in parallel, then one aggregate toast. */
    fun refreshAll() {
        val subs = SubscriptionRepository.subscriptions.filter { it.url != null }
        if (subs.isEmpty()) {
            showToast(str(com.interstellar.proxy.R.string.vm_no_sub_to_update), UiToast.Kind.Error)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _refreshing.value = true
            _busy.value = true
            val semaphore = kotlinx.coroutines.sync.Semaphore(3)
            var ok = 0
            try {
                kotlinx.coroutines.coroutineScope {
                    subs.forEach { sub ->
                        launch {
                            semaphore.withPermit {
                                val success = runCatching {
                                    val result = SubscriptionFetcher.fetch(sub.url!!)
                                    when (val parsed = SubscriptionParser.parse(result.body, sub.id)) {
                                        is SubscriptionParser.Result.Nodes -> {
                                            SubscriptionRepository.upsert(
                                                sub.copy(
                                                    nodes = parsed.nodes,
                                                    uploadBytes = result.uploadBytes,
                                                    downloadBytes = result.downloadBytes,
                                                    totalBytes = result.totalBytes,
                                                    expireSeconds = result.expireSeconds,
                                                    lastUpdated = System.currentTimeMillis(),
                                                ),
                                            )
                                            true
                                        }

                                        SubscriptionParser.Result.Empty -> false
                                    }
                                }.getOrDefault(false)
                                if (success) ok++
                            }
                        }
                    }
                }
                if (ok == subs.size) {
                    showToast(str(com.interstellar.proxy.R.string.vm_updated_all, ok), UiToast.Kind.Success)
                } else {
                    showToast(
                        str(com.interstellar.proxy.R.string.vm_updated_partial, ok, subs.size),
                        if (ok > 0) UiToast.Kind.Success else UiToast.Kind.Error,
                    )
                }
            } finally {
                _busy.value = false
                _refreshing.value = false
                // upserts regenerated the active config behind refreshProxyConfig's back
                refreshStaticGroups()
                _subscriptions.value = SubscriptionRepository.subscriptions.toList()
                _activeSubscriptionId.value = SubscriptionRepository.activeSubscriptionId
            }
        }
    }

    /** Edits an existing subscription's name / url. */
    fun updateSubscription(id: String, name: String, url: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val sub = SubscriptionRepository.get(id) ?: return@launch
            SubscriptionRepository.upsert(
                sub.copy(
                    name = name.trim().ifBlank { sub.name },
                    url = url?.trim()?.takeIf { it.isNotBlank() } ?: sub.url,
                ),
            )
            _subscriptions.value = SubscriptionRepository.subscriptions.toList()
            _activeSubscriptionId.value = SubscriptionRepository.activeSubscriptionId
        }
    }

    fun removeSubscription(id: String) {
        val wasActive = id == SubscriptionRepository.activeSubscriptionId
        SubscriptionRepository.remove(id)
        when {
            SubscriptionRepository.subscriptions.isEmpty() -> {
                // 删光了:节点/分组全清,停掉还在吃旧配置的内核
                _groups.value = emptyList()
                _delays.value = emptyMap()
                _staticGroups.value = emptyList()
                if (_status.value == Status.Started || _status.value == Status.Starting) {
                    com.interstellar.proxy.bg.BoxService.stop()
                }
            }

            // 删除的是激活订阅 → 激活项已顺延,重建配置并热重载
            wasActive -> refreshProxyConfig()
        }
        refreshStaticGroups()
        _subscriptions.value = SubscriptionRepository.subscriptions.toList()
        _activeSubscriptionId.value = SubscriptionRepository.activeSubscriptionId
        _mixSubscriptionIds.value = Settings.mixSubscriptionIds
    }

    fun activateSubscription(id: String) {
        if (id == _activeSubscriptionId.value) return
        SubscriptionRepository.activeSubscriptionId = id
        _activeSubscriptionId.value = id
        refreshSplitRuleStatus()
        // regenerate + hot-reload per core (mihomo API reload / Xray respawn /
        // sing-box service reload) — a bare serviceReload() only reaches sing-box
        refreshProxyConfig()
    }

    /**
     * Toggles mix mode. Enabling with no (valid) checks defaults to every
     * subscription, so the pool is never silently empty on first use.
     */
    fun setMixEnabled(enabled: Boolean) {
        if (enabled == _mixEnabled.value) return
        if (enabled &&
            Settings.mixSubscriptionIds.none { id -> SubscriptionRepository.get(id) != null }
        ) {
            Settings.mixSubscriptionIds =
                SubscriptionRepository.subscriptions.map { it.id }.toSet()
            _mixSubscriptionIds.value = Settings.mixSubscriptionIds
        }
        Settings.mixEnabled = enabled
        _mixEnabled.value = enabled
        applyPoolChange()
    }

    /** Checks / unchecks one subscription in the mix pool; at least one stays checked. */
    fun toggleMixSubscription(id: String) {
        if (!_mixEnabled.value) return
        val current = _mixSubscriptionIds.value
        if (id in current && current.size <= 1) {
            _message.value = str(com.interstellar.proxy.R.string.vm_keep_one_sub)
            return
        }
        val next = if (id in current) current - id else current + id
        Settings.mixSubscriptionIds = next
        _mixSubscriptionIds.value = next
        applyPoolChange()
    }

    /** Rebuilds the config from the new pool and hot-reloads the running core. */
    private fun applyPoolChange() {
        // refreshProxyConfig regenerates from the new pool and dispatches the
        // per-core reload (mihomo API / Xray respawn / sing-box serviceReload)
        refreshProxyConfig()
    }

    fun clearMessage() {
        _message.value = null
    }

    /** One-tap exit-IP probe; races public IP APIs through the running node. */
    fun probeNetwork() {
        if (_probe.value == ProbeState.Running) return
        _probe.value = ProbeState.Running
        viewModelScope.launch(Dispatchers.IO) {
            _probe.value = try {
                ProbeState.Done(com.interstellar.proxy.data.net.NetProbe.probe())
            } catch (e: Exception) {
                ProbeState.Failed(e.message ?: str(com.interstellar.proxy.R.string.vm_probe_failed))
            }
        }
    }

    private fun urlHost(url: String): String = runCatching {
        java.net.URI(url).host ?: url
    }.getOrDefault(url)
}
