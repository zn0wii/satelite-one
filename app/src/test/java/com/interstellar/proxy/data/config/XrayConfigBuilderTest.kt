package com.interstellar.proxy.data.config

import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Xray aborts the whole start on a duplicate outbound tag ("existing tag
 * found") — lock every mode against that class of error.
 */
class XrayConfigBuilderTest {

    private val nodes = listOf(
        ProxyNode(
            id = "1", name = "🇭🇰 香港 01", type = NodeType.VMESS,
            server = "hk1.example.com", port = 443, uuid = "uuid-1",
            tls = true, sni = "hk1.example.com",
        ),
        ProxyNode(
            id = "2", name = "🇸🇬 SG-02", type = NodeType.TROJAN,
            server = "sg2.example.net", port = 443, password = "pw", tls = true,
        ),
    )

    private fun outboundTags(mode: ConfigBuilder.OutboundMode, selected: String?): List<String> {
        val json = Json.parseToJsonElement(
            XrayConfigBuilder.build(
                nodes,
                ConfigBuilder.BuildOptions(mode = mode, selectedNodeTag = selected),
            ),
        ).jsonObject
        return json["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
    }

    @Test
    fun `rule mode with auto selection has no duplicate outbound tags`() {
        val tags = outboundTags(ConfigBuilder.OutboundMode.RULE, ConfigBuilder.AUTO_TAG)
        check(tags.size == tags.distinct().size) { "duplicate tags: ${tags.groupBy { it }.filterValues { it.size > 1 }.keys}" }
        check(tags.first() == "direct") { "balancer mode must lead with the freedom outbound" }
    }

    @Test
    fun `every mode emits unique outbound tags`() {
        for (mode in ConfigBuilder.OutboundMode.entries) {
            for (selected in listOf(null, ConfigBuilder.AUTO_TAG, "🇸🇬 SG-02")) {
                val tags = outboundTags(mode, selected)
                check(tags.size == tags.distinct().size) {
                    "mode=$mode selected=$selected duplicate tags: ${tags.groupBy { it }.filterValues { it.size > 1 }.keys}"
                }
            }
        }
    }

    private fun routingRules(opts: ConfigBuilder.BuildOptions): List<JsonObject> =
        Json.parseToJsonElement(XrayConfigBuilder.build(nodes, opts))
            .jsonObject["routing"]!!.jsonObject["rules"]!!.jsonArray
            .map { it.jsonObject }

    @Test
    fun `overseas proxy routes geolocation-!cn through the balancer before cn direct`() {
        val rules = routingRules(ConfigBuilder.BuildOptions(mode = ConfigBuilder.OutboundMode.RULE))
        // default build (auto → balancer): overseas rule must carry the balancer tag
        val overseas = rules.filter { r -> r["domain"]?.jsonArray?.any { it.jsonPrimitive.content == "geosite:geolocation-!cn" } == true }
        check(overseas.isEmpty()) { "overseas rule must be opt-in" }

        val enabled = routingRules(
            ConfigBuilder.BuildOptions(mode = ConfigBuilder.OutboundMode.RULE, overseasProxy = true),
        )
        val overseasOn = enabled.filter { r ->
            r["domain"]?.jsonArray?.any { it.jsonPrimitive.content == "geosite:geolocation-!cn" } == true
        }
        check(overseasOn.size == 1) { "geolocation-!cn rule missing/duplicated" }
        check(overseasOn.single()["balancerTag"]!!.jsonPrimitive.content == "auto-bal") { "rides the balancer" }
        val overseasIdx = enabled.indexOf(overseasOn.single())
        val cnIdx = enabled.indexOfFirst { r -> r["domain"]?.jsonArray?.any { it.jsonPrimitive.content == "geosite:cn" } == true }
        check(cnIdx < 0 || overseasIdx < cnIdx) { "overseas rule must match before the CN bypass" }
    }

    @Test
    fun `fallback direct sends unmatched traffic to the freedom outbound`() {
        val rules = routingRules(
            ConfigBuilder.BuildOptions(mode = ConfigBuilder.OutboundMode.RULE, fallbackDirect = true),
        )
        val last = rules.last()
        check(last["outboundTag"]!!.jsonPrimitive.content == "direct") {
            "catch-all must be direct, got ${last["outboundTag"]}"
        }
        // global mode keeps the proxy catch-all regardless of the fallback
        val global = routingRules(
            ConfigBuilder.BuildOptions(mode = ConfigBuilder.OutboundMode.GLOBAL, fallbackDirect = true),
        )
        check("balancerTag" in global.last() || global.last()["outboundTag"]!!.jsonPrimitive.content == "proxy") {
            "global catch-all must stay proxied"
        }
    }
}
