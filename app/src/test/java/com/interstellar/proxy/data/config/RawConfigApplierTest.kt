package com.interstellar.proxy.data.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Test

/**
 * RawConfigApplier keeps the original structure and only patches what the
 * in-app service model needs — these tests lock that contract per core.
 */
class RawConfigApplierTest {

    private val options = RawConfigApplier.Options(
        mode = ConfigBuilder.OutboundMode.RULE,
        bypassLan = true,
        bypassCn = true,
        overseasProxy = true,
        fallbackDirect = true,
        adBlock = true,
        apiSecret = "sec",
    )

    private val clashYaml = """
        # airport config with anchors
        mixed-port: 7890
        mode: global
        allow-lan: true
        secret: airport-secret
        tun:
          enable: true
          stack: gvisor
        anchors: &a
          type: ss
          cipher: aes-256-gcm
        proxies:
          - name: "香港 01"
            <<: *a
            server: hk.example.com
            port: 443
            password: pw
        proxy-groups:
          - name: "🚀 节点选择"
            type: select
            proxies:
              - "香港 01"
        rules:
          - DOMAIN-SUFFIX,example.com,🚀 节点选择
          - MATCH,🚀 节点选择
    """.trimIndent()

    @Test
    fun `clash - service keys are overridden, structure and anchors preserved`() {
        val out = RawConfigApplier.applyClash(clashYaml, options)
        check("mixed-port: 2080" in out) { "mixed-port" }
        check("external-controller: '127.0.0.1:19090'" in out) { "controller" }
        check("secret: 'sec'" in out) { "secret override" }
        check("allow-lan: false" in out) { "allow-lan" }
        check("mode: rule" in out) { "mode" }
        check(out.lines().none { it == "tun:" }) { "tun block stripped" }
        check("<<: *a" in out) { "yaml merge key preserved" }
        check("server: hk.example.com" in out) { "proxy body preserved" }
        check("store-selected: true" in out) { "profile appended" }
    }

    @Test
    fun `clash - built-in rules are prepended before original rules`() {
        val out = RawConfigApplier.applyClash(clashYaml, options)
        val lines = out.lines()
        val adsIdx = lines.indexOfFirst { it.contains("GEOSITE,category-ads-all,REJECT") }
        val overseasIdx = lines.indexOfFirst { it.contains("GEOSITE,geolocation-!cn,") }
        val cnIdx = lines.indexOfFirst { it.contains("GEOSITE,cn,DIRECT") }
        val originalIdx = lines.indexOfFirst { it.contains("DOMAIN-SUFFIX,example.com") }
        val matchIdx = lines.indexOfFirst { it.trimStart().startsWith("- MATCH,🚀") }
        check(adsIdx in lines.indices) { "ads rule" }
        check(overseasIdx in lines.indices) { "overseas rule" }
        // overseas rides the FIRST proxy-group of the original config
        check(lines[overseasIdx].contains("GEOSITE,geolocation-!cn,🚀 节点选择")) { lines[overseasIdx] }
        check(cnIdx in lines.indices) { "cn rule" }
        check(adsIdx < originalIdx && cnIdx < originalIdx) { "injected rules precede originals" }
        // fallback MATCH,DIRECT sits right before the original final MATCH
        val fallbackIdx = lines.indexOfFirst { it.trimStart().startsWith("- MATCH,DIRECT") }
        check(fallbackIdx in lines.indices) { "fallback MATCH" }
        check(fallbackIdx == matchIdx - 1) { "fallback directly before original MATCH" }
        // indentation follows the original list style
        check(lines[adsIdx].startsWith("          - GEOSITE") || lines[adsIdx].trimStart().startsWith("- GEOSITE")) {
            "list indentation preserved: ${lines[adsIdx]}"
        }
    }

    @Test
    fun `clash - missing rules block is appended`() {
        val raw = """
            proxies:
              - name: "n1"
                type: ss
                server: s
                port: 1
                cipher: aes-256-gcm
                password: p
        """.trimIndent()
        val out = RawConfigApplier.applyClash(raw, options.copy(bypassCn = true))
        check("rules:" in out && "GEOSITE,cn,DIRECT" in out) { out }
    }

    @Test
    fun `singbox - clash api, tun, mixed and rule injection`() {
        val raw = """
            {
              "outbounds": [
                {"type": "selector", "tag": "选择", "outbounds": ["hk"]},
                {"type": "vmess", "tag": "hk", "server": "hk.example.com"}
              ],
              "route": {"rules": [{"domain": ["a.com"], "outbound": "hk"}]}
            }
        """.trimIndent()
        val out = RawConfigApplier.applySingbox(raw, options)
        val json = Json.parseToJsonElement(out).jsonObject

        val clashApi = json["experimental"]!!.jsonObject["clash_api"]!!.jsonObject
        check(clashApi["external_controller"]!!.jsonPrimitive.content == "127.0.0.1:19090")
        check(clashApi["secret"]!!.jsonPrimitive.content == "sec")
        check(clashApi["default_mode"]!!.jsonPrimitive.content == "rule")

        val inbounds = json["inbounds"]!!.jsonArray
        check(inbounds.any { it.jsonObject["type"]!!.jsonPrimitive.content == "tun" })
        check(inbounds.any {
            it.jsonObject["type"]!!.jsonPrimitive.content == "mixed" &&
                it.jsonObject["listen_port"]!!.jsonPrimitive.content == "2080"
        })

        val outbounds = json["outbounds"]!!.jsonArray.map { it.jsonObject }
        check(outbounds.any { it["type"]!!.jsonPrimitive.content == "direct" }) { "direct outbound appended" }
        check(outbounds.any { it["type"]!!.jsonPrimitive.content == "block" }) { "block outbound appended" }

        val rules = json["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val cnRule = rules.first { r -> r["rule_set"]?.jsonArray?.toString()?.contains("geosite-cn") == true }
        check(cnRule["outbound"]!!.jsonPrimitive.content == "__interstellar_direct")
        val overseas = rules.first { r -> r["rule_set"]?.jsonArray?.toString()?.contains("geolocation-!cn") == true }
        check(overseas["outbound"]!!.jsonPrimitive.content == "选择") { "overseas rides first selector" }
        // injected rules precede the original one
        check(rules.indexOf(cnRule) < rules.indexOfFirst { it["domain"] != null })
        // fallback flips route.final to the direct outbound
        check(json["route"]!!.jsonObject["final"]!!.jsonPrimitive.content == "__interstellar_direct")

        val sets = json["route"]!!.jsonObject["rule_set"]!!.jsonArray.map { it.jsonObject }
        check(sets.any { it["tag"]!!.jsonPrimitive.content == "geosite-geolocation-!cn" })
    }

    @Test
    fun `singbox - existing tun keeps its identity but gains auto_route`() {
        val raw = """
            {
              "inbounds": [{"type": "tun", "tag": "my-tun", "address": ["10.0.0.1/30"], "auto_route": false}],
              "outbounds": [{"type": "direct", "tag": "direct"}]
            }
        """.trimIndent()
        val out = RawConfigApplier.applySingbox(raw, options)
        val tun = Json.parseToJsonElement(out).jsonObject["inbounds"]!!.jsonArray
            .map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "tun" }
        check(tun["tag"]!!.jsonPrimitive.content == "my-tun") { "original tun kept" }
        check(tun["auto_route"]!!.jsonPrimitive.content == "true") { "auto_route forced" }
    }

    @Test
    fun `xray - inbounds replaced, dns-out added, rules prepended`() {
        val raw = """
            {
              "inbounds": [{"port": 10808, "protocol": "socks", "settings": {"auth": "password"}}],
              "outbounds": [
                {"tag": "proxy", "protocol": "vless", "settings": {}},
                {"tag": "direct", "protocol": "freedom"}
              ],
              "routing": {"rules": [{"domain": ["a.com"], "outboundTag": "proxy"}]}
            }
        """.trimIndent()
        val out = RawConfigApplier.applyXray(raw, options)
        val root = JSONObject(out)

        val inbounds = root.getJSONArray("inbounds")
        check(inbounds.length() == 1)
        check(inbounds.getJSONObject(0).getInt("port") == 2080)
        check(inbounds.getJSONObject(0).getString("tag") == "socks-in")

        val outbounds = root.getJSONArray("outbounds")
        val tags = generateSequence(0) { if (it + 1 < outbounds.length()) it + 1 else null }
            .map { outbounds.getJSONObject(it).optString("tag") }.toList()
        check("dns-out" in tags) { "dns outbound added" }
        check("__interstellar_block" in tags) { "blackhole added" }

        val rules = root.getJSONObject("routing").getJSONArray("rules")
        val first = rules.getJSONObject(0)
        check(first.optString("outboundTag") == "dns-out") { "dns hijack leads" }
        val overseas = generateSequence(0) { if (it + 1 < rules.length()) it + 1 else null }
            .map { rules.getJSONObject(it) }
            .first { it.optJSONArray("domain")?.toString()?.contains("geolocation-!cn") == true }
        check(overseas.optString("outboundTag") == "proxy")
        // fallback catch-all closes the list
        val last = rules.getJSONObject(rules.length() - 1)
        check(last.optString("outboundTag") == "direct" && last.optString("network") == "tcp,udp")
    }
}
