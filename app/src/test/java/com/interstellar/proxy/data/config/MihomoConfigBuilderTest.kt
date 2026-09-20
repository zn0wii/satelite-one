package com.interstellar.proxy.data.config

import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ProxyNode
import com.interstellar.proxy.data.subscription.YamlToJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Generates a representative mihomo config and validates the emitted YAML
 * by round-tripping it through kaml (YamlToJson) — catches emitter
 * indentation/quoting bugs before they reach the core (mihomo rejects
 * malformed configs outright).
 */
class MihomoConfigBuilderTest {

    private val nodes = listOf(
        ProxyNode(
            id = "1", name = "🇭🇰 香港 01 · 测试: 节点", type = NodeType.VMESS,
            server = "hk1.example.com", port = 443, uuid = "uuid-1",
            tls = true, sni = "hk1.example.com", network = "ws", wsPath = "/path",
        ),
        ProxyNode(
            id = "2", name = "🇸🇬 SG-02", type = NodeType.HYSTERIA2,
            server = "sg2.example.net", port = 8443, password = "pw",
            hy2ObfsPassword = "obfs", upMbps = 100, downMbps = 500,
        ),
        ProxyNode(
            id = "3", name = "1.2.3.4 relay", type = NodeType.TROJAN,
            server = "1.2.3.4", port = 443, password = "tp", tls = true,
        ),
    )

    private val options = ConfigBuilder.BuildOptions(
        mode = ConfigBuilder.OutboundMode.RULE,
        selectedNodeTag = "🇸🇬 SG-02",
        includeTun = true,
    )

    @Test
    fun `emits yaml that kaml can parse with the expected structure`() {
        val yaml = MihomoConfigBuilder.build(nodes, options)
        println("=====GENERATED=====")
        println(yaml)
        println("=====END=====")
        val doc = YamlToJson.convert(yaml)?.jsonObject ?: error("generated yaml failed to parse")

        check("proxies" in doc) { "proxies missing" }
        check("proxy-groups" in doc) { "proxy-groups missing" }
        check("rules" in doc) { "rules missing" }
        check("sniffer" in doc) { "sniffer missing (domain rules depend on it)" }
        check("dns" in doc) { "dns missing" }
        val dns = doc["dns"]!!.jsonObject
        check("proxy-server-nameserver" in dns) { "node-server direct dns missing" }
        check(doc["mixed-port"]!!.jsonPrimitive.content == "2080") { "mixed-port" }
        check(doc["external-controller"]!!.jsonPrimitive.content == "127.0.0.1:9090") { "clash api" }
        check("tun" !in doc) { "tun must be absent (sidecar proxy-only + hev bridge)" }

        val proxies = doc["proxies"]!!.jsonArray
        check(proxies.size == 3) { "3 proxies, got ${proxies.size}" }
        val proxyObjs = proxies.map { it.jsonObject }
        val typeOf = { p: JsonObject -> p["type"]!!.jsonPrimitive.content }
        check(proxyObjs.any { typeOf(it) == "vmess" }) { "vmess" }
        check(proxyObjs.any { typeOf(it) == "hysteria2" }) { "hysteria2" }
        check(proxyObjs.any { typeOf(it) == "trojan" }) { "trojan" }
        check(proxyObjs.any { p -> p["name"]!!.jsonPrimitive.content == "🇭🇰 香港 01 · 测试: 节点" }) { "quoted CJK/punct name" }
        val vmess = proxyObjs.first { typeOf(it) == "vmess" }
        check("ws-opts" in vmess) { "ws opts" }
        check(vmess["servername"]!!.jsonPrimitive.content == "hk1.example.com") { "vmess sni" }
        val hy2 = proxyObjs.first { typeOf(it) == "hysteria2" }
        check(hy2["up"]!!.jsonPrimitive.content == "100 Mbps") { "hy2 up" }
        check(hy2["obfs-password"]!!.jsonPrimitive.content == "obfs") { "hy2 obfs" }

        val groups = doc["proxy-groups"]!!.jsonArray.map { it.jsonObject }
        check(groups.first()["name"]!!.jsonPrimitive.content == "proxy") { "main select" }
        check(groups.any { it["name"]!!.jsonPrimitive.content == "auto" }) { "urltest group" }
        val mainMembers = groups.first()["proxies"]!!.jsonArray.map { it.jsonPrimitive.content }
        check(mainMembers.first() == "auto" && mainMembers.size == 4) { "selector members: $mainMembers" }

        val rules = doc["rules"]!!.jsonArray.map { it.jsonPrimitive.content }
        check(rules.last() == "MATCH,proxy") { "final rule: ${rules.last()}" }
        check("GEOSITE,cn,DIRECT" in rules) { "cn bypass" }
        check(rules.any { it.startsWith("IP-CIDR,192.168.0.0/16,DIRECT") }) { "lan bypass" }
    }

    @Test
    fun `proxy mode strips the tun block`() {
        val yaml = MihomoConfigBuilder.build(nodes, options.copy(includeTun = false))
        check(!yaml.contains("\ntun:")) { "tun block must be absent" }
    }

    private fun rulesOf(opts: ConfigBuilder.BuildOptions): List<String> =
        YamlToJson.convert(MihomoConfigBuilder.build(nodes, opts))!!
            .jsonObject["rules"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `overseas proxy adds geolocation-!cn before the cn bypass`() {
        val rules = rulesOf(options.copy(overseasProxy = true))
        val overseas = rules.indexOfFirst { it.startsWith("GEOSITE,geolocation-!cn,") }
        val cn = rules.indexOfFirst { it == "GEOSITE,cn,DIRECT" }
        check(overseas >= 0) { "geolocation-!cn rule missing" }
        check(cn < 0 || overseas < cn) { "overseas rule must match before the CN bypass" }
        check(rules[overseas] == "GEOSITE,geolocation-!cn,proxy") { rules[overseas] }
    }

    @Test
    fun `fallback direct makes MATCH go DIRECT and keeps global mode proxied`() {
        val rule = rulesOf(options.copy(fallbackDirect = true))
        check(rule.last() == "MATCH,DIRECT") { "final rule: ${rule.last()}" }
        val global = rulesOf(options.copy(mode = ConfigBuilder.OutboundMode.GLOBAL, fallbackDirect = true))
        check(global.last() == "MATCH,proxy") { "global ignores the fallback: ${global.last()}" }
    }
}
