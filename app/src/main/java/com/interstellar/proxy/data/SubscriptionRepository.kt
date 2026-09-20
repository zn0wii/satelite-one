package com.interstellar.proxy.data

import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.data.config.ConfigBuilder
import com.interstellar.proxy.data.model.ProxyNode
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * File-backed store for subscriptions, their nodes and the current selection.
 * Regenerates the active sing-box config on every mutation.
 */
object SubscriptionRepository {
    private const val TAG = "SubscriptionRepo"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Serializable
    data class Subscription(
        val id: String,
        val name: String,
        val url: String? = null,
        val nodes: List<ProxyNode> = emptyList(),
        val uploadBytes: Long = 0,
        val downloadBytes: Long = 0,
        val totalBytes: Long = 0,
        val expireSeconds: Long = 0,
        val lastUpdated: Long = 0,
    )

    private val dir: File
        get() = File(InterstellarApplication.application.filesDir, "subscriptions").also { it.mkdirs() }

    private val indexFile: File
        get() = File(InterstellarApplication.application.filesDir, "subscriptions.json")

    var subscriptions: MutableList<Subscription> = load()
        private set

    var activeSubscriptionId: String
        get() = Settings.activeSubscriptionId
        set(value) {
            Settings.activeSubscriptionId = value
        }

    var selectedNodeId: String
        get() = Settings.selectedNodeId
        set(value) {
            Settings.selectedNodeId = value
        }

    private fun load(): MutableList<Subscription> {
        val list = runCatching {
            if (indexFile.exists()) {
                json.decodeFromString<List<Subscription>>(indexFile.readText()).toMutableList()
            } else {
                mutableListOf()
            }
        }.getOrDefault(mutableListOf())
        // drop mix checks pointing at subscriptions that no longer exist
        val mixIds = Settings.mixSubscriptionIds
        if (mixIds.isNotEmpty()) {
            val pruned = mixIds.filterTo(mutableSetOf()) { id -> list.any { it.id == id } }
            if (pruned.size != mixIds.size) Settings.mixSubscriptionIds = pruned
        }
        return list
    }

    @Synchronized
    fun save() {
        runCatching {
            indexFile.writeText(json.encodeToString(subscriptions.toList()))
        }
    }

    fun get(id: String): Subscription? = subscriptions.find { it.id == id }

    @Synchronized
    fun upsert(subscription: Subscription) {
        val index = subscriptions.indexOfFirst { it.id == subscription.id }
        if (index >= 0) {
            subscriptions[index] = subscription
        } else {
            subscriptions.add(subscription)
        }
        if (activeSubscriptionId.isBlank()) activeSubscriptionId = subscription.id
        save()
        regenerateActiveConfig()
    }

    @Synchronized
    fun remove(id: String) {
        subscriptions.removeAll { it.id == id }
        if (activeSubscriptionId == id) {
            activeSubscriptionId = subscriptions.firstOrNull()?.id ?: ""
        }
        val remaining = Settings.mixSubscriptionIds - id
        Settings.mixSubscriptionIds =
            if (Settings.mixEnabled && remaining.isEmpty() && subscriptions.isNotEmpty()) {
                // keep the mix pool non-empty: re-check everything left
                subscriptions.map { it.id }.toSet()
            } else {
                remaining
            }
        save()
        regenerateActiveConfig()
    }

    fun activeSubscription(): Subscription? = get(activeSubscriptionId)

    /**
     * The node pool the generated config runs on. Mix on = union of the
     * checked subscriptions (in store order), off = the active subscription.
     * Single derivation shared by the repository and the UI.
     */
    fun poolOf(
        subscriptions: List<Subscription>,
        activeSubscriptionId: String,
        mixEnabled: Boolean,
        mixSubscriptionIds: Set<String>,
    ): List<ProxyNode> = if (mixEnabled) {
        subscriptions.filter { it.id in mixSubscriptionIds }.flatMap { it.nodes }
    } else {
        subscriptions.find { it.id == activeSubscriptionId }?.nodes ?: emptyList()
    }

    /** Subscriptions checked into the mix pool (store order). */
    fun mixedSubscriptions(): List<Subscription> =
        subscriptions.filter { it.id in Settings.mixSubscriptionIds }

    fun activeNodes(): List<ProxyNode> =
        poolOf(subscriptions, activeSubscriptionId, Settings.mixEnabled, Settings.mixSubscriptionIds)

    fun newSubscriptionId(): String = UUID.randomUUID().toString()

    /** Last config generation/check failure, for UI diagnostics. */
    @Volatile
    var lastConfigError: String? = null
        private set

    /**
     * Regenerates filesDir/active.json from the current node pool (the active
     * subscription, or the mix union when Mix is on).
     * Returns null when there is nothing to run.
     */
    @Synchronized
    fun regenerateActiveConfig(includeTun: Boolean = true): String? {
        // built-in rule sets must exist on disk before local rule-set paths
        // are embedded into the config
        RulesStore.ensureRules(InterstellarApplication.application)

        val mix = Settings.mixEnabled
        if (!mix && activeSubscription() == null) {
            lastConfigError = "未选择订阅"
            return null
        }

        val nodes = activeNodes()
        if (nodes.isEmpty()) {
            lastConfigError = when {
                mix && mixedSubscriptions().isEmpty() -> "Mix 未勾选订阅"
                mix -> "所选订阅中没有可用节点"
                else -> "订阅中没有可用节点"
            }
            return null
        }

        var selectedTag = Settings.selectedOutboundTag.takeIf { it.isNotBlank() }
            ?: ConfigBuilder.tagFor(nodes, selectedNodeId)
            ?: ConfigBuilder.AUTO_TAG
        // smart mode: bake in the engine's current pick (fall back to auto
        // on a cold start); the engine refines it via hot-switches later
        if (selectedTag == com.interstellar.proxy.data.config.ConfigBuilder.SMART_TAG) {
            selectedTag = Settings.smartActiveTag.takeIf { it.isNotBlank() }
                ?: ConfigBuilder.AUTO_TAG
        }
        val regionGroups = Settings.regionGroupsEnabled
        if (!regionGroups &&
            selectedTag != ConfigBuilder.AUTO_TAG &&
            ConfigBuilder.nodeFilterRulesActive(selectedTag, nodes, regionGroupsEnabled = true)
        ) {
            selectedTag = ConfigBuilder.AUTO_TAG
            Settings.selectedOutboundTag = ConfigBuilder.AUTO_TAG
        }
        val opts = ConfigBuilder.BuildOptions(
            mode = Settings.outboundMode,
            bypassLan = Settings.bypassLanEnabled,
            bypassCn = Settings.bypassCnEnabled,
            overseasProxy = Settings.overseasProxyEnabled,
            fallbackDirect = Settings.fallbackDirectEnabled,
            adBlock = Settings.adBlockEnabled,
            selectedNodeTag = selectedTag,
            apiSecret = Settings.apiSecret,
            customRules = CustomRulesStore.rules.toList(),
            dnsOverrides = DnsOverridesStore.enabled(),
            applyNodeFilterRules = Settings.splitRulesEnabled &&
                Settings.outboundMode == ConfigBuilder.OutboundMode.RULE,
            regionGroupsEnabled = regionGroups,
            includeTun = includeTun,
            simpleRules = com.interstellar.proxy.data.SimpleRulesStore.enabled(),
        )
        val coreKind = Settings.coreKind
        val content =
            if (coreKind == com.interstellar.proxy.core.CoreKind.MIHOMO) {
                com.interstellar.proxy.data.config.MihomoConfigBuilder.build(nodes, opts)
            } else if (coreKind == com.interstellar.proxy.core.CoreKind.XRAY) {
                com.interstellar.proxy.data.config.XrayConfigBuilder.build(nodes, opts)
            } else {
                ConfigBuilder.build(nodes, opts)
            }
        return try {
            // libbox only validates sing-box JSON; sidecars self-validate at spawn
            if (coreKind == com.interstellar.proxy.core.CoreKind.SINGBOX) {
                Libbox.checkConfig(content)
            }
            ConfigStore.writeActiveConfig(content)
            lastConfigError = null
            content
        } catch (e: Exception) {
            lastConfigError = "配置校验失败: ${e.message}"
            android.util.Log.e(TAG, "config check failed: ${e.message}\n$content", e)
            null
        }
    }

    suspend fun regenerateActiveConfigAsync(): String? = withContext(Dispatchers.IO) {
        regenerateActiveConfig()
    }

    /**
     * Fetches a URL subscription and updates the store.
     * Returns a user-facing message, or null when the sub is not refreshable.
     */
    suspend fun refresh(id: String): String? = withContext(Dispatchers.IO) {
        val sub = get(id) ?: return@withContext null
        val url = sub.url ?: return@withContext "本地订阅不支持刷新"
        try {
            val result = com.interstellar.proxy.data.net.SubscriptionFetcher.fetch(url)
            when (val parsed = com.interstellar.proxy.data.subscription.SubscriptionParser.parse(result.body, id)) {
                is com.interstellar.proxy.data.subscription.SubscriptionParser.Result.Nodes -> {
                    upsert(
                        sub.copy(
                            nodes = parsed.nodes,
                            uploadBytes = result.uploadBytes,
                            downloadBytes = result.downloadBytes,
                            totalBytes = result.totalBytes,
                            expireSeconds = result.expireSeconds,
                            lastUpdated = System.currentTimeMillis(),
                        ),
                    )
                    "已刷新 ${sub.name}:${parsed.nodes.size} 个节点"
                }

                com.interstellar.proxy.data.subscription.SubscriptionParser.Result.Empty ->
                    "刷新后内容无法解析:${sub.name}"
            }
        } catch (e: Exception) {
            "刷新失败 ${sub.name}:${e.message}"
        }
    }

    /** Refreshes every URL subscription, keeping the selected node when possible. */
    suspend fun refreshAll(): List<String> = withContext(Dispatchers.IO) {
        subscriptions.filter { it.url != null }.map { refresh(it.id) }.filterNotNull()
    }

    fun selectNode(nodeId: String) {
        selectedNodeId = nodeId
        regenerateActiveConfig()
    }
}
