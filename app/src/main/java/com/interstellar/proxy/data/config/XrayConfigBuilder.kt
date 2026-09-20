package com.interstellar.proxy.data.config

import com.interstellar.proxy.data.SimpleRouteRule
import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ProxyNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the Xray-core JSON config from the unified node model, aligned with
 * [MihomoConfigBuilder]'s rule/DNS semantics so switching cores keeps
 * behavior identical.
 *
 * Xray has no selector outbound and no REST API — the selected node is baked
 * into the config (tag "proxy"), and "auto" becomes a leastPing balancer over
 * every node outbound (prefix "n|" keeps them selectable). Switching nodes
 * means regenerating and restarting the core.
 *
 * VPN mode is the same hev-socks5-tunnel bridge as mihomo: Xray only ever
 * runs a local socks inbound; the TUN rides in through 127.0.0.1:<port>.
 */
object XrayConfigBuilder {

    const val GROUP_TAG = ConfigBuilder.GROUP_TAG
    const val AUTO_TAG = ConfigBuilder.AUTO_TAG
    private const val PROXY_TAG = "proxy"
    private const val NODE_PREFIX = "n|"
    private const val BALANCER_TAG = "auto-bal"
    private const val TEST_URL = "https://www.gstatic.com/generate_204"

    /** Node types Xray can express; others are skipped with [skippedReport]. */
    private val SUPPORTED = setOf(
        NodeType.SHADOWSOCKS, NodeType.VMESS, NodeType.VLESS, NodeType.TROJAN,
        NodeType.SOCKS, NodeType.HTTP, NodeType.WIREGUARD, NodeType.HYSTERIA2,
    )

    /** Human-readable summary of the nodes dropped by the Xray protocol matrix. */
    var skippedReport: String? = null
        private set

    fun build(nodes: List<ProxyNode>, options: ConfigBuilder.BuildOptions): String {
        val usable = nodes.filter { it.type in SUPPORTED && it.shadowTls == null && it.network != "quic" }
        val unsupported = nodes.size - usable.size
        skippedReport = if (unsupported > 0) {
            "已跳过 $unsupported 个 Xray 不支持的节点(tuic/anytls/ssh/shadow-tls/quic)"
        } else {
            null
        }
        if (usable.isEmpty()) {
            throw IllegalStateException("订阅中没有 Xray 支持的节点 (支持 ss/vmess/vless/trojan/socks/http/wireguard/hysteria2)")
        }

        val tags = ConfigBuilder.tagsFor(usable)
        val nodeOutbounds = usable.zip(tags).mapNotNull { (node, tag) -> nodeToOutbound(node, NODE_PREFIX + tag) }

        // selection: a supported leaf is baked in as "proxy"; auto / unsupported
        // leaf degrades to the leastPing balancer over every node
        val selectedTag = options.selectedNodeTag
        val selectedIndex = tags.indexOf(selectedTag)
        val useBalancer = selectedTag == AUTO_TAG || selectedIndex < 0

        val outbounds = JSONArray()
        val rules = JSONArray()
        // answer tunnel DNS locally: internal module (DoH via proxy / domestic
        // UDP) instead of relaying raw UDP 53 through the node
        rules.put(
            JSONObject().put("type", "field")
                .put("inboundTag", JSONArray(listOf("socks-in")))
                .put("port", 53)
                .put("network", "udp,tcp")
                .put("outboundTag", "dns-out"),
        )
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))
        root.put("dns", buildDns(usable, options))
        root.put(
            "inbounds",
            JSONArray().put(
                JSONObject()
                    .put("tag", "socks-in")
                    .put("listen", "127.0.0.1")
                    .put("port", options.mixedPort)
                    .put("protocol", "socks")
                    .put(
                        "settings",
                        JSONObject().put("udp", true).put("auth", "noauth"),
                    )
                    // sniff connections back to domains so domain rules still
                    // match (mirror of mihomo's sniffer — no fake-ip here)
                    .put(
                        "sniffing",
                        JSONObject()
                            .put("enabled", true)
                            .put("destOverride", JSONArray(listOf("http", "tls", "quic"))),
                    ),
            ),
        )

        val ruleMode = options.mode == ConfigBuilder.OutboundMode.RULE
        when {
            // direct mode: everything out the first (freedom) outbound, no rules
            options.mode == ConfigBuilder.OutboundMode.DIRECT -> {
                outbounds.put(freedomOutbound())
                nodeOutbounds.forEach(outbounds::put)
                outbounds.put(blockOutbound())
            }

            // global mode: everything through the proxy, built-ins ignored (mihomo parity)
            !ruleMode -> {
                outbounds.put(
                    if (useBalancer) freedomOutbound() else nodeOutbound(usable[selectedIndex], PROXY_TAG),
                )
                nodeOutbounds.forEach(outbounds::put)
                outbounds.put(blockOutbound())
                rules.put(catchAllProxyRule(useBalancer))
            }

            // rule mode
            else -> {
                outbounds.put(
                    if (useBalancer) freedomOutbound() else nodeOutbound(usable[selectedIndex], PROXY_TAG),
                )
                nodeOutbounds.forEach(outbounds::put)
                // with a balancer the first slot is already the freedom
                // outbound tagged "direct" — a second one collides and Xray
                // aborts with "existing tag found: direct"
                if (!useBalancer) outbounds.put(freedomOutbound())
                outbounds.put(blockOutbound())
                buildRules(usable, tags, options, useBalancer).forEach(rules::put)
            }
        }
        // never first — Xray's default outbound is outbounds[0]
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))

        val routing = JSONObject()
            .put("domainStrategy", "IPIfNonMatch")
            .put("rules", rules)
        if (useBalancer) {
            routing.put(
                "balancers",
                JSONArray().put(
                    JSONObject()
                        .put("tag", BALANCER_TAG)
                        .put("selector", JSONArray(listOf(NODE_PREFIX)))
                        .put("strategy", JSONObject().put("type", "leastPing")),
                ),
            )
            root.put(
                "observatory",
                JSONObject()
                    .put("subjectSelector", JSONArray(listOf(NODE_PREFIX)))
                    .put("probeURL", TEST_URL)
                    .put("probeInterval", "30s")
                    .put("enableConcurrency", true),
            )
        }
        root.put("routing", routing)
        root.put("outbounds", outbounds)
        return root.toString(2)
    }

    // ---- dns ----

    /**
     * DNS mirrors mihomo's split: geosite:cn + node server domains via plain
     * domestic UDP (both IPs are excluded from the VPN routes — mihomo's
     * proxy-server-nameserver escape hatch), everything else via DoH that
     * rides the proxy (internal DNS requests follow the routing rules).
     * Tunnel port-53 traffic is hijacked into this module (dns-out rule), so
     * resolution never depends on UDP relay through the node.
     */
    private fun buildDns(nodes: List<ProxyNode>, options: ConfigBuilder.BuildOptions): JSONObject {
        val directDomains = mutableListOf("geosite:cn")
        nodes.mapTo(directDomains) { "domain:" + it.server.trim().removeSuffix(".") }
        val dns = JSONObject()
            .put("queryStrategy", "UseIPv4")
            .put(
                "servers",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("address", "https://1.1.1.1/dns-query")
                            .put("skipFallback", true),
                    )
                    .put(
                        JSONObject()
                            .put("address", "223.5.5.5")
                            .put("domains", JSONArray(directDomains))
                            .put("skipFallback", true),
                    ),
            )
        if (options.dnsOverrides.isNotEmpty()) {
            val hosts = JSONObject()
            for (entry in options.dnsOverrides) {
                for (domain in entry.parsedDomains()) hosts.put(domain, entry.ip)
            }
            dns.put("hosts", hosts)
        }
        return dns
    }

    // ---- routing rules ----

    private fun buildRules(
        nodes: List<ProxyNode>,
        tags: List<String>,
        options: ConfigBuilder.BuildOptions,
        useBalancer: Boolean,
    ): List<JSONObject> = buildList {
        // user's manual domain rules beat every built-in rule
        for (rule in options.simpleRules) {
            val suffix = rule.domain.trim().removePrefix("*.").removeSuffix(".")
            if (suffix.isBlank()) continue
            val field = JSONObject().put("type", "field").put("domain", JSONArray(listOf("domain:$suffix")))
            when (rule.action) {
                SimpleRouteRule.Action.DIRECT -> field.put("outboundTag", "direct")
                SimpleRouteRule.Action.PROXY ->
                    if (useBalancer) field.put("balancerTag", BALANCER_TAG) else field.put("outboundTag", PROXY_TAG)

                SimpleRouteRule.Action.NODE -> {
                    val nodeIdx = rule.nodeId?.let { id -> nodes.indexOfFirst { it.id == id } } ?: -1
                    if (nodeIdx >= 0) field.put("outboundTag", NODE_PREFIX + tags[nodeIdx]) else continue
                }
            }
            add(field)
        }
        if (options.bypassLan) {
            add(
                JSONObject().put("type", "field")
                    .put(
                        "ip",
                        JSONArray(listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7")),
                    )
                    .put("outboundTag", "direct"),
            )
        }
        if (options.adBlock) {
            add(
                JSONObject().put("type", "field")
                    .put("domain", JSONArray(listOf("geosite:category-ads-all")))
                    .put("outboundTag", "block"),
            )
        }
        // overseas (geolocation-!cn) domains ride the proxy — matched before
        // the CN bypass so whitelist-style routing wins for overlapping domains
        if (options.overseasProxy) {
            val overseas = JSONObject().put("type", "field")
                .put("domain", JSONArray(listOf("geosite:geolocation-!cn")))
            add(
                if (useBalancer) {
                    overseas.put("balancerTag", BALANCER_TAG)
                } else {
                    overseas.put("outboundTag", PROXY_TAG)
                },
            )
        }
        if (options.bypassCn) {
            add(
                JSONObject().put("type", "field")
                    .put("domain", JSONArray(listOf("geosite:cn")))
                    .put("outboundTag", "direct"),
            )
            add(
                JSONObject().put("type", "field")
                    .put("ip", JSONArray(listOf("geoip:cn")))
                    .put("outboundTag", "direct"),
            )
        }
        if (options.fallbackDirect) {
            // whitelist-style: only rule-matched domains ride the proxy
            add(JSONObject().put("type", "field").put("network", "tcp,udp").put("outboundTag", "direct"))
        } else {
            add(catchAllProxyRule(useBalancer))
        }
    }

    private fun catchAllProxyRule(useBalancer: Boolean): JSONObject {
        val rule = JSONObject().put("type", "field").put("network", "tcp,udp")
        return if (useBalancer) rule.put("balancerTag", BALANCER_TAG) else rule.put("outboundTag", PROXY_TAG)
    }

    // ---- outbounds ----

    private fun freedomOutbound() = JSONObject().put("tag", "direct").put("protocol", "freedom")

    private fun blockOutbound() = JSONObject().put("tag", "block").put("protocol", "blackhole")

    private fun nodeOutbound(node: ProxyNode, tag: String?): JSONObject =
        nodeToOutbound(node, tag ?: "")!!

    private fun nodeToOutbound(node: ProxyNode, tag: String): JSONObject? {
        val settings = when (node.type) {
            NodeType.SHADOWSOCKS -> JSONObject().put(
                "servers",
                JSONArray().put(
                    JSONObject()
                        .put("address", node.server)
                        .put("port", node.port)
                        .put("method", node.method ?: "aes-256-gcm")
                        .put("password", node.password ?: "")
                        .apply { pluginFor(node)?.let { (name, opts) -> put("plugin", name).put("pluginOpts", opts) } },
                ),
            )

            NodeType.VMESS -> JSONObject().put(
                "vnext",
                JSONArray().put(
                    JSONObject()
                        .put("address", node.server)
                        .put("port", node.port)
                        .put(
                            "users",
                            JSONArray().put(
                                JSONObject()
                                    .put("id", node.uuid ?: "")
                                    .put("alterId", node.alterId ?: 0)
                                    .put("security", node.security ?: "auto")
                                    .put("encryption", "auto"),
                            ),
                        ),
                ),
            )

            NodeType.VLESS -> JSONObject().put(
                "vnext",
                JSONArray().put(
                    JSONObject()
                        .put("address", node.server)
                        .put("port", node.port)
                        .put(
                            "users",
                            JSONArray().put(
                                JSONObject()
                                    .put("id", node.uuid ?: "")
                                    .put("encryption", "none")
                                    .apply { node.flow?.let { put("flow", it) } },
                            ),
                        ),
                ),
            )

            NodeType.TROJAN -> JSONObject().put(
                "servers",
                JSONArray().put(
                    JSONObject()
                        .put("address", node.server)
                        .put("port", node.port)
                        .put("password", node.password ?: ""),
                ),
            )

            NodeType.SOCKS, NodeType.HTTP -> {
                val server = JSONObject()
                    .put("address", node.server)
                    .put("port", node.port)
                if (!node.username.isNullOrBlank() || !node.password.isNullOrBlank()) {
                    server.put(
                        "users",
                        JSONArray().put(
                            JSONObject()
                                .apply { node.username?.let { put("user", it) } }
                                .apply { node.password?.let { put("pass", it) } },
                        ),
                    )
                }
                JSONObject().put("servers", JSONArray().put(server))
            }

            NodeType.HYSTERIA2 -> JSONObject()
                .put("version", 2)
                .put("address", node.server)
                .put("port", node.port)

            NodeType.WIREGUARD -> {
                val wg = node.wireguard ?: return null
                JSONObject()
                    .put("secretKey", wg.privateKey)
                    .put("address", JSONArray(wg.localAddress.ifEmpty { listOf("172.16.0.2/32") }))
                    .put(
                        "peers",
                        JSONArray().put(
                            JSONObject()
                                .apply { wg.peerPublicKey?.let { put("publicKey", it) } }
                                .apply { wg.preSharedKey?.let { put("preSharedKey", it) } }
                                .put("endpoint", "${node.server}:${node.port}")
                                .apply { wg.reserved?.takeIf { it.size >= 3 }?.let { put("reserved", JSONArray(it.take(3))) } },
                        ),
                    )
                    .apply { wg.mtu?.let { put("mtu", it) } }
            }

            else -> return null
        }

        val out = JSONObject()
            .put("tag", tag)
            .put("protocol", protocolOf(node))
            .put("settings", settings)
        streamSettingsFor(node)?.let { out.put("streamSettings", it) }
        return out
    }

    private fun protocolOf(node: ProxyNode) = when (node.type) {
        NodeType.SHADOWSOCKS -> "shadowsocks"
        NodeType.SOCKS -> "socks"
        NodeType.HYSTERIA2 -> "hysteria" // Xray's name; version 2 = hysteria2
        else -> node.type.wire
    }

    /** sing-box plugin name → (xray plugin name, pluginOpts string). */
    private fun pluginFor(node: ProxyNode): Pair<String, String>? {
        val plugin = node.plugin ?: return null
        val opts = node.pluginOpts?.entries?.joinToString(";") { (k, v) -> "$k=$v" } ?: return null
        return when {
            plugin.contains("obfs") -> "obfs-local" to opts
            plugin.contains("v2ray-plugin") -> "v2ray-plugin" to opts
            else -> null
        }
    }

    private fun streamSettingsFor(node: ProxyNode): JSONObject? {
        val stream = JSONObject()
        if (node.type == NodeType.HYSTERIA2) {
            // QUIC rides the "hysteria" transport; auth/obfs/bandwidth are
            // transport-level (hysteriaSettings + finalmask), TLS is inherent
            stream.put("network", "hysteria")
                .put(
                    "hysteriaSettings",
                    JSONObject().put("version", 2).put("auth", node.password ?: ""),
                )
                .put("security", "tls")
                .put(
                    "tlsSettings",
                    JSONObject()
                        .put("serverName", node.sni ?: node.server)
                        .put("alpn", JSONArray(listOf("h3"))),
                )
            val finalMask = JSONObject()
            if (!node.hy2ObfsPassword.isNullOrBlank()) {
                finalMask.put(
                    "udp",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "salamander")
                            .put("settings", JSONObject().put("password", node.hy2ObfsPassword)),
                    ),
                )
            }
            if (node.upMbps != null || node.downMbps != null) {
                finalMask.put(
                    "quicParams",
                    JSONObject()
                        .apply { node.upMbps?.let { put("brutalUp", "$it Mbps") } }
                        .apply { node.downMbps?.let { put("brutalDown", "$it Mbps") } }
                        .put("congestion", "brutal"),
                )
            }
            if (finalMask.length() > 0) stream.put("finalmask", finalMask)
            return stream
        }
        when (node.network) {
            "ws" -> stream.put("network", "ws").put(
                "wsSettings",
                JSONObject()
                    .put("path", node.wsPath ?: "/")
                    .apply {
                        node.headers?.takeIf { it.isNotEmpty() }?.let { headers ->
                            put("headers", JSONObject(headers))
                        }
                    },
            )

            "grpc" -> stream.put("network", "grpc").put(
                "grpcSettings",
                JSONObject().put("serviceName", node.grpcServiceName ?: ""),
            )

            "h2", "http" -> stream.put("network", "http").put(
                "httpSettings",
                JSONObject()
                    .put("path", node.httpPath ?: "/")
                    .apply { node.httpHost?.takeIf { it.isNotEmpty() }?.let { put("host", JSONArray(it)) } },
            )

            else -> {} // plain tcp
        }

        when {
            node.reality != null -> stream.put("security", "reality").put(
                "realitySettings",
                JSONObject()
                    .put("serverName", node.sni ?: node.server)
                    .put("fingerprint", node.fingerprint ?: "chrome")
                    .put("publicKey", node.reality.publicKey)
                    .apply { node.reality.shortId?.let { put("shortId", it) } }
                    .put("spiderX", ""),
            )

            node.tls || node.type == NodeType.TROJAN || (node.type == NodeType.SOCKS && node.tls) -> stream.put("security", "tls").put(
                "tlsSettings",
                JSONObject()
                    .put("serverName", node.sni ?: node.server)
                    // v26 removed allowInsecure (→ pinnedPeerCertSha256); without
                    // a pin, self-signed nodes surface as TLS dial errors instead
                    .apply { node.alpn?.let { put("alpn", JSONArray(it)) } }
                    .apply { node.fingerprint?.let { put("fingerprint", it) } },
            )
        }
        return if (stream.length() == 0) null else stream
    }

    /** Route exclusions for the sidecar VPN (node servers + DNS upstreams). */
    fun routeExclusions(nodes: List<ProxyNode>): List<String> = buildList {
        for (node in nodes) {
            val host = node.server.trim()
            if (host.isNotEmpty()) add(host)
        }
        // 1.1.1.1 is NOT excluded: app DNS rides the tunnel to it
        addAll(listOf("223.5.5.5", "119.29.29.29"))
    }
}
