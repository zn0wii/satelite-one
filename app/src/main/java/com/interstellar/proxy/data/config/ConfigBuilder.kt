package com.interstellar.proxy.data.config

import com.interstellar.proxy.data.NodeMatcher
import com.interstellar.proxy.data.model.CustomRouteRule
import com.interstellar.proxy.data.model.DomainMatchType
import com.interstellar.proxy.data.model.DnsOverrideEntry
import com.interstellar.proxy.data.model.NodeFilterMode
import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ProxyNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the sing-box JSON config from the unified node model.
 * Kotlin counterpart of interstellar-proxy's config/builder.rs + dns_build.rs,
 * adapted to the sing-box 1.14 (rule-actions, typed DNS) schema.
 */
object ConfigBuilder {

    const val GROUP_TAG = "手动选择"
    const val AUTO_TAG = "auto"
    /** Placeholder for the upcoming smart-switch mode (not yet functional). */
    const val SMART_TAG = "smart"
    const val DIRECT_TAG = "direct"
    const val BLOCK_TAG = "block"
    private const val DNS_HOSTS_TAG = "dns-hosts"

    /** Tags that node names must never collide with. */
    private val RESERVED_TAGS =
        setOf(GROUP_TAG, AUTO_TAG, SMART_TAG, DIRECT_TAG, BLOCK_TAG, "dns-out", DNS_HOSTS_TAG, "tun-in", "mixed-in")

    data class BuildOptions(
        val mode: OutboundMode = OutboundMode.RULE,
        val bypassLan: Boolean = true,
        val bypassCn: Boolean = true,
        /** Route geolocation-!cn (overseas) domains through the proxy group (rule mode). */
        val overseasProxy: Boolean = false,
        /** Rule-mode fallback for unmatched traffic: direct instead of the proxy group. */
        val fallbackDirect: Boolean = false,
        val adBlock: Boolean = true,
        val selectedNodeTag: String? = null,
        val mixedPortEnabled: Boolean = true,
        val mixedPort: Int = 2080,
        // keep in sync with MihomoCore.API_PORT (19090 — never clash's 9090 default)
        val apiPort: Int = 19090,
        val apiSecret: String = "",
        val customRules: List<CustomRouteRule> = emptyList(),
        /** User domain→IP injections, resolved by a hosts DNS server first. */
        val dnsOverrides: List<DnsOverrideEntry> = emptyList(),
        /**
         * When true, domain→filtered-urltest rules are in the config even if
         * a leaf node is selected. Default traffic still uses [selectedNodeTag].
         */
        val applyNodeFilterRules: Boolean = true,
        /** Per-country urltest groups (香港 · 自动, …). Off: only 自动 + nodes. */
        val regionGroupsEnabled: Boolean = false,
        /** When false, skip TUN so url-test can run without claiming the VPN. */
        val includeTun: Boolean = true,
        /** Mobile-simple domain→action rules (SimpleRulesStore). */
        val simpleRules: List<com.interstellar.proxy.data.SimpleRouteRule> = emptyList(),
    )

    /** A derived urltest group: region auto, or a custom-rule filter. */
    data class DerivedGroup(
        val tag: String,
        val members: List<String>,
        val ruleId: String? = null,
    )

    enum class OutboundMode { RULE, GLOBAL, DIRECT }

    fun build(nodes: List<ProxyNode>, options: BuildOptions): String {
        val tags = dedupeTags(nodes)
        val used = tags.toMutableSet().apply { addAll(RESERVED_TAGS) }
        val regionGroups = if (options.regionGroupsEnabled) deriveRegionGroups(tags, used) else emptyList()
        val customGroups = if (options.mode == OutboundMode.RULE && options.applyNodeFilterRules) {
            deriveCustomGroups(tags, options.customRules, used)
        } else {
            emptyList()
        }
        val json = buildJsonObject {
            putJsonObject("log") {
                put("level", "info")
                put("timestamp", true)
            }
            putJsonObject("dns") { buildDns(options) }
            putJsonArray("inbounds") { buildInbounds(options) }
            putJsonArray("outbounds") { buildOutbounds(nodes, tags, options, regionGroups, customGroups) }
            putJsonObject("route") { buildRoute(options, customGroups, resolveSimpleRules(nodes, options)) }
            putJsonObject("experimental") {
                putJsonObject("clash_api") {
                    put("external_controller", "127.0.0.1:${options.apiPort}")
                    if (options.apiSecret.isNotBlank()) put("secret", options.apiSecret)
                    put("default_mode", when (options.mode) {
                        OutboundMode.RULE -> "rule"
                        OutboundMode.GLOBAL -> "global"
                        OutboundMode.DIRECT -> "direct"
                    })
                }
                putJsonObject("cache_file") {
                    put("enabled", true)
                }
            }
        }
        return json.toString()
    }

    // ---- inbounds ----

    private fun kotlinx.serialization.json.JsonArrayBuilder.buildInbounds(options: BuildOptions) {
        if (options.includeTun) {
            add(
                buildJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    putJsonArray("address") {
                        // v4-only TUN: the underlying network often has no IPv6
                        // exit, and a v6 tun address makes apps dial AAAA targets
                        // that the direct outbound can never reach — every such
                        // connection dies with ERR_CONNECTION_RESET. Matches the
                        // mihomo sidecar VPN and CMFA's default (allowIpv6=false).
                        add("172.19.0.1/30")
                    }
                    put("mtu", 9000)
                    put("auto_route", true)
                    put("stack", "mixed")
                    if (options.bypassLan) {
                        putJsonArray("route_exclude_address") {
                            add("10.0.0.0/8")
                            add("172.16.0.0/12")
                            add("192.168.0.0/16")
                        }
                    }
                },
            )
        }
        if (options.mixedPortEnabled) {
            add(
                buildJsonObject {
                    put("type", "mixed")
                    put("tag", "mixed-in")
                    put("listen", "127.0.0.1")
                    put("listen_port", options.mixedPort)
                },
            )
        }
    }

    // ---- outbounds ----

    private fun kotlinx.serialization.json.JsonArrayBuilder.buildOutbounds(
        nodes: List<ProxyNode>,
        tags: List<String>,
        options: BuildOptions,
        regionGroups: List<DerivedGroup>,
        customGroups: List<DerivedGroup>,
    ) {
        val selectorMembers = buildList {
            add(AUTO_TAG)
            addAll(regionGroups.map { it.tag })
            addAll(tags)
        }
        val defaultTag = options.selectedNodeTag?.takeIf { it in selectorMembers } ?: AUTO_TAG
        // main selector: auto (urltest-all) + per-region urltest + every node
        add(
            buildJsonObject {
                put("type", "selector")
                put("tag", GROUP_TAG)
                putJsonArray("outbounds") {
                    selectorMembers.forEach { add(it) }
                }
                put("default", defaultTag)
                put("interrupt_exist_connections", false)
            },
        )
        // auto urltest
        if (nodes.isNotEmpty()) {
            add(urltestOutbound(AUTO_TAG, tags))
        }
        regionGroups.forEach { add(urltestOutbound(it.tag, it.members)) }
        customGroups.forEach { add(urltestOutbound(it.tag, it.members)) }
        // node outbounds (shadow-tls detours referenced inline)
        val usedTags = mutableSetOf<String>()
        val extras = mutableListOf<JsonElement>()
        nodes.zip(tags).forEach { (node, tag) ->
            val detourTag: String?
            if (node.shadowTls != null && node.type == NodeType.VMESS) {
                var stTag = "$tag-shadowtls"
                var i = 2
                while (!usedTags.add(stTag)) {
                    stTag = "$tag-shadowtls-${i++}"
                }
                detourTag = stTag
                extras.add(buildShadowTlsOutbound(node, stTag))
            } else {
                detourTag = null
            }
            add(nodeToOutbound(node, tag, detourTag))
        }
        extras.forEach { add(it) }
        // fixed outbounds
        add(buildJsonObject { put("type", "direct"); put("tag", DIRECT_TAG) })
        add(buildJsonObject { put("type", "block"); put("tag", BLOCK_TAG) })
        // note: no "dns" outbound — removed in sing-box 1.13, replaced by the
        // hijack-dns route action above
    }

    private fun urltestOutbound(tag: String, members: List<String>): JsonObject = buildJsonObject {
        put("type", "urltest")
        put("tag", tag)
        putJsonArray("outbounds") { members.forEach { add(it) } }
        put("url", "https://www.gstatic.com/generate_204")
        put("interval", "5m")
        put("tolerance", 50)
        put("idle_timeout", "30m")
    }

    /**
     * One urltest per detected region so the node page can switch
     * "auto among HK / SG / …" without flattening back to a single node.
     */
    private fun deriveRegionGroups(tags: List<String>, used: MutableSet<String>): List<DerivedGroup> {
        val buckets = linkedMapOf<NodeMatcher.Region, MutableList<String>>()
        for (tag in tags) {
            val region = NodeMatcher.regionOf(tag) ?: continue
            buckets.getOrPut(region) { mutableListOf() }.add(tag)
        }
        return buckets.map { (region, members) ->
            val preferred = "${region.flag} ${region.name}"
            val tag = uniqueTag(preferred, used)
            DerivedGroup(tag, members)
        }
    }

    private fun deriveCustomGroups(
        tags: List<String>,
        rules: List<CustomRouteRule>,
        used: MutableSet<String>,
    ): List<DerivedGroup> {
        return rules.mapNotNull { rule ->
            if (!rule.enabled) return@mapNotNull null
            // direct rules need no node group — routed to the fixed direct outbound
            if (rule.filterMode == NodeFilterMode.DIRECT) return@mapNotNull null
            if (rule.parsedMatchValues().isEmpty()) return@mapNotNull null
            val keywords = rule.nodeKeywords.map { it.trim() }.filter { it.isNotEmpty() }
            if (keywords.isEmpty()) return@mapNotNull null
            val members = NodeMatcher.filterTags(
                tags,
                keywords,
                include = rule.filterMode == NodeFilterMode.INCLUDE,
            )
            if (members.isEmpty()) return@mapNotNull null
            val tag = uniqueTag(customRuleTag(rule.id), used)
            DerivedGroup(tag, members, ruleId = rule.id)
        }
    }

    fun customRuleTag(id: String): String = "rule-${id.replace("-", "").take(8)}"

    /**
     * True when [selectedTag] is auto or a per-region urltest group — the
     * modes where keyword-filter split rules are allowed to override paths.
     */
    fun nodeFilterRulesActive(
        selectedTag: String?,
        nodes: List<ProxyNode>,
        regionGroupsEnabled: Boolean = true,
    ): Boolean {
        val tag = selectedTag?.takeIf { it.isNotBlank() } ?: AUTO_TAG
        if (tag == AUTO_TAG) return true
        if (!regionGroupsEnabled) return false
        val tags = dedupeTags(nodes)
        val used = tags.toMutableSet().apply { addAll(RESERVED_TAGS) }
        return deriveRegionGroups(tags, used).any { it.tag == tag }
    }

    private fun uniqueTag(preferred: String, used: MutableSet<String>): String {
        if (used.add(preferred)) return preferred
        var i = 2
        var candidate = "$preferred · 自动"
        if (used.add(candidate)) return candidate
        while (!used.add(candidate)) {
            candidate = "$preferred · 自动 ($i)"
            i++
        }
        return candidate
    }

    private fun shadowTlsTag(baseTag: String, used: MutableSet<String>): String {
        var tag = "$baseTag-shadowtls"
        var i = 2
        while (!used.add(tag)) {
            tag = "$baseTag-shadowtls-${i++}"
        }
        return tag
    }

    private fun buildShadowTlsOutbound(node: ProxyNode, tag: String): JsonObject = buildJsonObject {
        put("type", "shadowtls")
        put("tag", tag)
        put("server", node.server)
        put("server_port", node.port)
        put("version", node.shadowTls?.version ?: 3)
        node.shadowTls?.password?.let { put("password", it) }
        putJsonObject("tls") {
            put("enabled", true)
            put("server_name", node.shadowTls?.sni ?: node.sni ?: node.server)
        }
    }

    fun nodeToOutbound(node: ProxyNode, tag: String, shadowTlsDetour: String? = null): JsonObject = buildJsonObject {
        put("tag", tag)
        if (shadowTlsDetour != null) put("detour", shadowTlsDetour)
        when (node.type) {
            NodeType.SHADOWSOCKS -> {
                put("type", "shadowsocks")
                put("server", node.server)
                put("server_port", node.port)
                put("method", node.method ?: "aes-256-gcm")
                node.password?.let { put("password", it) }
                if (node.plugin != null) {
                    put("plugin", node.plugin)
                    node.pluginOpts?.let { opts ->
                        putJsonObject("plugin_opts") {
                            opts.forEach { (k, v) -> put(k, v) }
                        }
                    }
                }
            }

            NodeType.VMESS -> {
                put("type", "vmess")
                put("server", node.server)
                put("server_port", node.port)
                put("uuid", node.uuid ?: "")
                put("security", node.security ?: "auto")
                put("alter_id", node.alterId ?: 0)
                buildTls(this, node)
                buildTransport(this, node)
            }

            NodeType.VLESS -> {
                put("type", "vless")
                put("server", node.server)
                put("server_port", node.port)
                put("uuid", node.uuid ?: "")
                node.flow?.let { put("flow", it) }
                buildTls(this, node)
                buildTransport(this, node)
            }

            NodeType.TROJAN -> {
                put("type", "trojan")
                put("server", node.server)
                put("server_port", node.port)
                put("password", node.password ?: "")
                buildTls(this, node)
                buildTransport(this, node)
            }

            NodeType.HYSTERIA2 -> {
                put("type", "hysteria2")
                put("server", node.server)
                put("server_port", node.port)
                node.password?.let { put("password", it) }
                node.hy2ObfsPassword?.let {
                    putJsonObject("obfs") {
                        put("type", "salamander")
                        put("password", it)
                    }
                }
                node.upMbps?.let { put("up_mbps", it) }
                node.downMbps?.let { put("down_mbps", it) }
                putJsonObject("tls") {
                    put("enabled", true)
                    node.sni?.let { put("server_name", it) } ?: put("server_name", node.server)
                    put("insecure", node.insecure ?: false)
                    node.alpn?.let { alpn -> putJsonArray("alpn") { alpn.forEach { add(it) } } }
                }
            }

            NodeType.TUIC -> {
                put("type", "tuic")
                put("server", node.server)
                put("server_port", node.port)
                node.uuid?.let { put("uuid", it) }
                node.password?.let { put("password", it) }
                node.congestionControl?.let { put("congestion_control", it) }
                node.udpRelayMode?.let { put("udp_relay_mode", it) }
                node.reduceRtt?.let { put("reduce_rtt", it) }
                putJsonObject("tls") {
                    put("enabled", true)
                    node.sni?.let { put("server_name", it) } ?: put("server_name", node.server)
                    put("insecure", node.insecure ?: false)
                    node.alpn?.let { alpn -> putJsonArray("alpn") { alpn.forEach { add(it) } } }
                }
            }

            NodeType.SOCKS -> {
                put("type", "socks")
                put("server", node.server)
                put("server_port", node.port)
                put("version", "5")
                node.username?.let { put("username", it) }
                node.password?.let { put("password", it) }
                if (node.tls) buildTls(this, node)
            }

            NodeType.HTTP -> {
                put("type", "http")
                put("server", node.server)
                put("server_port", node.port)
                node.username?.let { put("username", it) }
                node.password?.let { put("password", it) }
                if (node.tls) buildTls(this, node)
            }

            NodeType.WIREGUARD -> {
                put("type", "wireguard")
                put("server", node.server)
                put("server_port", node.port)
                putJsonArray("local_address") {
                    (node.wireguard?.localAddress ?: listOf("172.16.0.2/32")).forEach { add(it) }
                }
                put("private_key", node.wireguard?.privateKey ?: "")
                node.wireguard?.peerPublicKey?.let { put("peer_public_key", it) }
                node.wireguard?.preSharedKey?.let { put("pre_shared_key", it) }
                node.wireguard?.reserved?.let { reserved ->
                    putJsonArray("reserved") { reserved.forEach { add(it) } }
                }
                node.wireguard?.mtu?.let { put("mtu", it) }
            }

            NodeType.ANYTLS -> {
                put("type", "anytls")
                put("server", node.server)
                put("server_port", node.port)
                node.password?.let { put("password", it) }
                putJsonObject("tls") {
                    put("enabled", true)
                    node.sni?.let { put("server_name", it) } ?: put("server_name", node.server)
                    put("insecure", node.insecure ?: false)
                }
            }

            NodeType.SSH -> {
                put("type", "ssh")
                put("server", node.server)
                put("server_port", node.port)
                node.sshUser?.let { put("user", it) }
                node.sshKey?.let { put("private_key", it) }
            }

            NodeType.UNKNOWN -> put("type", "direct")
        }
    }

    private fun buildTls(obj: kotlinx.serialization.json.JsonObjectBuilder, node: ProxyNode) {
        if (!node.tls && node.reality == null) return
        obj.putJsonObject("tls") {
            put("enabled", true)
            val sni = node.sni ?: node.server
            put("server_name", sni)
            put("insecure", node.insecure ?: false)
            node.alpn?.let { alpn -> putJsonArray("alpn") { alpn.forEach { add(it) } } }
            if (node.fingerprint != null) {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", node.fingerprint)
                }
            }
            node.reality?.let { reality ->
                if (reality.publicKey.isNotBlank()) {
                    putJsonObject("reality") {
                        put("enabled", true)
                        put("public_key", reality.publicKey)
                        reality.shortId?.let { put("short_id", it) }
                    }
                }
            }
        }
    }

    private fun buildTransport(obj: kotlinx.serialization.json.JsonObjectBuilder, node: ProxyNode) {
        when (node.network) {
            "ws" -> obj.putJsonObject("transport") {
                put("type", "ws")
                put("path", node.wsPath ?: "/")
                node.headers?.let { headers ->
                    putJsonObject("headers") {
                        headers.forEach { (k, v) -> put(k, v) }
                    }
                }
            }

            "grpc" -> obj.putJsonObject("transport") {
                put("type", "grpc")
                node.grpcServiceName?.let { put("service_name", it) }
            }

            "h2", "http" -> obj.putJsonObject("transport") {
                put("type", "http")
                node.httpPath?.let { put("path", it) }
                node.httpHost?.let { hosts ->
                    putJsonArray("host") { hosts.forEach { add(it) } }
                }
            }
        }
    }

    // ---- dns / route ----

    private fun kotlinx.serialization.json.JsonObjectBuilder.buildDns(options: BuildOptions) {
        putJsonArray("servers") {
            add(
                buildJsonObject {
                    put("tag", "dns-local")
                    put("type", "local")
                },
            )
            add(
                buildJsonObject {
                    put("tag", "dns-cn")
                    put("type", "udp")
                    put("server", "223.5.5.5")
                },
            )
            add(
                buildJsonObject {
                    put("tag", "dns-remote")
                    put("type", "https")
                    put("server", "1.1.1.1")
                    // sing-box 1.12+ rejects detour→empty direct outbound with a
                    // fatal error; in DIRECT mode an omitted detour already dials
                    // straight out the system interface
                    if (options.mode != OutboundMode.DIRECT) put("detour", GROUP_TAG)
                },
            )
            // user-injected domain→IP mappings (hosts semantics)
            if (options.dnsOverrides.isNotEmpty()) {
                add(
                    buildJsonObject {
                        put("tag", DNS_HOSTS_TAG)
                        put("type", "hosts")
                        putJsonObject("predefined") {
                            for (entry in options.dnsOverrides) {
                                for (domain in entry.parsedDomains()) {
                                    put(domain, entry.ip)
                                }
                            }
                        }
                    },
                )
            }
        }
        putJsonArray("rules") {
            // injected answers win over everything else — node server
            // addresses included, so forcing a node domain is possible
            if (options.dnsOverrides.isNotEmpty()) {
                add(
                    buildJsonObject {
                        putJsonArray("domain") {
                            options.dnsOverrides
                                .flatMap { it.parsedDomains() }
                                .distinct()
                                .forEach { add(it) }
                        }
                        put("server", DNS_HOSTS_TAG)
                    },
                )
            }
            add(
                buildJsonObject {
                    put("outbound", "any")
                    put("server", "dns-local")
                },
            )
            if (options.adBlock) {
                add(
                    buildJsonObject {
                        putJsonArray("rule_set") { add("category-ads-all") }
                        put("action", "reject")
                    },
                )
            }
            if (options.mode == OutboundMode.RULE && options.bypassCn) {
                add(
                    buildJsonObject {
                        putJsonArray("rule_set") { add("geosite-cn") }
                        put("server", "dns-cn")
                    },
                )
            }
        }
        // DIRECT mode dials dns-remote (1.1.1.1 DoH) without a detour, which is
        // unreachable in CN — resolve via CN DNS instead, mirroring mihomo's
        // DIRECT-mode nameserver (223.5.5.5)
        put(
            "final",
            when (options.mode) {
                OutboundMode.DIRECT -> "dns-cn"
                else -> "dns-remote"
            },
        )
        put("strategy", "prefer_ipv4")
        put("independent_cache", true)
    }

    /** nodeId → outbound tag for the current node pool. */
    private fun resolveSimpleRules(
        nodes: List<ProxyNode>,
        options: BuildOptions,
    ): List<Pair<String, String>> = options.simpleRules.mapNotNull { rule ->
        val outbound = when (rule.action) {
            com.interstellar.proxy.data.SimpleRouteRule.Action.DIRECT -> DIRECT_TAG
            com.interstellar.proxy.data.SimpleRouteRule.Action.PROXY -> GROUP_TAG
            com.interstellar.proxy.data.SimpleRouteRule.Action.NODE ->
                tagFor(nodes, rule.nodeId ?: return@mapNotNull null) ?: return@mapNotNull null
        }
        rule.domain.trim().removePrefix("*.").removeSuffix(".") to outbound
    }.filter { it.first.isNotBlank() }

    private fun kotlinx.serialization.json.JsonObjectBuilder.buildRoute(
        options: BuildOptions,
        customGroups: List<DerivedGroup>,
        simpleRules: List<Pair<String, String>>,
    ) {
        putJsonArray("rules") {
            add(buildJsonObject { put("action", "sniff") })
            add(
                buildJsonObject {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                },
            )
            // user's manual domain rules beat every built-in rule
            for ((suffix, outbound) in simpleRules) {
                add(
                    buildJsonObject {
                        putJsonArray("domain_suffix") { add(suffix) }
                        put("outbound", outbound)
                    },
                )
            }
            if (options.bypassLan) {
                add(
                    buildJsonObject {
                        put("ip_is_private", true)
                        put("outbound", DIRECT_TAG)
                    },
                )
            }
            // ads blocked first so tracker domains never reach the CN rule
            if (options.adBlock) {
                add(
                    buildJsonObject {
                        putJsonArray("rule_set") { add("category-ads-all") }
                        put("outbound", BLOCK_TAG)
                    },
                )
            }
            // user domain rules beat the generic CN bypass (so a CN site can
            // still be forced through a filtered node set). Armed whenever
            // the split-rules master switch is on — independent of auto/manual.
            if (options.mode == OutboundMode.RULE && options.applyNodeFilterRules) {
                val byId = customGroups.associateBy { it.ruleId }
                for (rule in options.customRules) {
                    if (!rule.enabled) continue
                    val values = rule.parsedMatchValues()
                    if (values.isEmpty()) continue
                    val outbound = when (rule.filterMode) {
                        // direct rules need no derived group
                        NodeFilterMode.DIRECT -> DIRECT_TAG
                        else -> byId[rule.id]?.tag ?: continue
                    }
                    add(
                        buildJsonObject {
                            when (rule.matchType) {
                                DomainMatchType.DOMAIN -> putJsonArray("domain") { values.forEach { add(it) } }
                                DomainMatchType.DOMAIN_SUFFIX -> putJsonArray("domain_suffix") { values.forEach { add(it) } }
                                DomainMatchType.DOMAIN_KEYWORD -> putJsonArray("domain_keyword") { values.forEach { add(it) } }
                            }
                            put("outbound", outbound)
                        },
                    )
                }
            }
            // overseas (geolocation-!cn) domains ride the proxy — matched
            // before the CN bypass so whitelist-style routing wins for a
            // domain that appears in both lists
            if (options.mode == OutboundMode.RULE && options.overseasProxy) {
                add(
                    buildJsonObject {
                        putJsonArray("rule_set") { add("geosite-geolocation-!cn") }
                        put("outbound", GROUP_TAG)
                    },
                )
            }
            if (options.mode == OutboundMode.RULE && options.bypassCn) {
                add(
                    buildJsonObject {
                        putJsonArray("rule_set") {
                            add("geosite-cn")
                            add("geoip-cn")
                        }
                        put("outbound", DIRECT_TAG)
                    },
                )
            }
        }
        putJsonArray("rule_set") {
            if (options.adBlock) {
                add(ruleSetJson("category-ads-all", com.interstellar.proxy.data.RulesStore.adsAll))
            }
            if (options.mode == OutboundMode.RULE && options.overseasProxy) {
                add(ruleSetJson("geosite-geolocation-!cn", com.interstellar.proxy.data.RulesStore.geolocationNotCn))
            }
            if (options.mode == OutboundMode.RULE && options.bypassCn) {
                add(ruleSetJson("geosite-cn", com.interstellar.proxy.data.RulesStore.geositeCn))
                add(ruleSetJson("geoip-cn", com.interstellar.proxy.data.RulesStore.geoipCn))
            }
        }
        put("final", when {
            options.mode == OutboundMode.DIRECT -> DIRECT_TAG
            // whitelist-style: only rule-matched domains ride the proxy
            options.mode == OutboundMode.RULE && options.fallbackDirect -> DIRECT_TAG
            else -> GROUP_TAG
        })
        put("auto_detect_interface", true)
    }

    // ---- helpers ----

    /**
     * Built-in rule set: local file when available (bundled in APK),
     * remote download as fallback. Also used by RawConfigApplier.
     */
    internal fun ruleSetJson(tag: String, asset: com.interstellar.proxy.data.RulesStore.RuleAsset): JsonObject {
        // runCatching: fall to the remote branch when the app context is
        // unavailable (JVM unit tests) instead of crashing config generation
        val file = runCatching { com.interstellar.proxy.data.RulesStore.fileOf(asset) }.getOrNull()
        return if (file != null && file.isFile && file.length() > 8) {
            buildJsonObject {
                put("tag", tag)
                put("type", "local")
                put("format", "binary")
                put("path", file.absolutePath)
            }
        } else {
            buildJsonObject {
                put("tag", tag)
                put("type", "remote")
                put("format", "binary")
                put("url", "https://raw.githubusercontent.com/SagerNet/sing-${if (tag.startsWith("geoip")) "geoip" else "geosite"}/rule-set/$tag.srs")
                put("download_detour", DIRECT_TAG)
            }
        }
    }

    private fun dedupeTags(nodes: List<ProxyNode>): List<String> {
        val used = mutableSetOf<String>()
        return nodes.map { node ->
            var base = node.name.trim().ifBlank { "${node.server}:${node.port}" }
            base = base.replace(Regex("[\\r\\n\"\\\\]"), " ").trim()
            var tag = base
            var index = 2
            while (tag in used || tag in RESERVED_TAGS) {
                tag = "$base ($index)"
                index++
            }
            used.add(tag)
            tag
        }
    }

    fun tagsFor(nodes: List<ProxyNode>): List<String> = dedupeTags(nodes)

    fun tagFor(nodes: List<ProxyNode>, nodeId: String): String? {
        val tags = dedupeTags(nodes)
        val index = nodes.indexOfFirst { it.id == nodeId }
        return if (index >= 0) tags[index] else null
    }
}
