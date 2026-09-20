package com.interstellar.proxy.data.config

import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ProxyNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * sing-box config invariants that the core rejects outright at start —
 * kept here so a builder change can't reintroduce a fatal startup error.
 */
class ConfigBuilderTest {

    private val nodes = listOf(
        ProxyNode(
            id = "1", name = "🇭🇰 香港 01", type = NodeType.VMESS,
            server = "hk1.example.com", port = 443, uuid = "uuid-1",
            tls = true, sni = "hk1.example.com",
        ),
    )

    private fun dnsServers(mode: ConfigBuilder.OutboundMode): List<JsonObject> {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            // adBlock/bypassCn off: their rule-sets hit RulesStore, which needs
            // the Android Application context and is irrelevant to DNS shape
            ConfigBuilder.build(
                nodes,
                ConfigBuilder.BuildOptions(mode = mode, adBlock = false, bypassCn = false),
            ),
        ).jsonObject
        return json["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
    }

    @Test
    fun `direct mode omits dns detour - empty direct detour is a fatal sing-box error`() {
        val servers = dnsServers(ConfigBuilder.OutboundMode.DIRECT)
        val remote = servers.first { it["tag"]!!.jsonPrimitive.content == "dns-remote" }
        check("detour" !in remote) { "dns-remote must not carry a detour in DIRECT mode" }
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            ConfigBuilder.build(
                nodes,
                ConfigBuilder.BuildOptions(
                    mode = ConfigBuilder.OutboundMode.DIRECT,
                    adBlock = false,
                    bypassCn = false,
                ),
            ),
        ).jsonObject
        val dns = json["dns"]!!.jsonObject
        check(dns["final"]!!.jsonPrimitive.content == "dns-cn") {
            "DIRECT mode must not resolve via dns-remote (bare 1.1.1.1 DoH is unreachable in CN)"
        }
    }

    @Test
    fun `proxied modes detour remote dns through the selector group`() {
        val remote = dnsServers(ConfigBuilder.OutboundMode.RULE)
            .first { it["tag"]!!.jsonPrimitive.content == "dns-remote" }
        check(remote["detour"]!!.jsonPrimitive.content == "proxy") { "dns-remote detour" }
    }

    @Test
    fun `rule-mode fallback direct sends unmatched traffic to the direct outbound`() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            ConfigBuilder.build(
                nodes,
                ConfigBuilder.BuildOptions(
                    mode = ConfigBuilder.OutboundMode.RULE,
                    adBlock = false,
                    bypassCn = false,
                    fallbackDirect = true,
                ),
            ),
        ).jsonObject
        check(json["route"]!!.jsonObject["final"]!!.jsonPrimitive.content == "direct") {
            "fallbackDirect must set route.final to the direct outbound"
        }
    }

    @Test
    fun `default rule mode and global mode keep the proxy as fallback`() {
        for (mode in listOf(ConfigBuilder.OutboundMode.RULE, ConfigBuilder.OutboundMode.GLOBAL)) {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(
                ConfigBuilder.build(
                    nodes,
                    ConfigBuilder.BuildOptions(mode = mode, adBlock = false, bypassCn = false),
                ),
            ).jsonObject
            check(json["route"]!!.jsonObject["final"]!!.jsonPrimitive.content == "proxy") {
                "$mode must keep the proxy group as route.final"
            }
        }
    }
}
