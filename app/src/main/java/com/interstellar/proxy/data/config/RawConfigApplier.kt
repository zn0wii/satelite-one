package com.interstellar.proxy.data.config

import com.interstellar.proxy.data.RulesStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.json.JSONArray
import org.json.JSONObject

/**
 * Feeds a retained raw subscription config to the matching core with minimal,
 * surgical edits — the original structure (proxies, groups, DNS, rules) is
 * preserved; we only fix what the in-app service model depends on and prepend
 * the built-in geo rules the user enabled. Deliberately NOT a rewrite: airport
 * configs carry YAML anchors, provider quirks and hand-tuned rules.
 *
 *  - clash → mihomo: line-based edits (YAML anchors survive byte-for-byte)
 *  - sing-box → sing-box: JSON tree edits via kotlinx.serialization
 *  - Xray → Xray: JSON tree edits via org.json; inbounds are replaced with
 *    the app's socks bridge (the hev TUN tunnel only speaks to 127.0.0.1:2080)
 */
object RawConfigApplier {

    data class Options(
        val mode: ConfigBuilder.OutboundMode = ConfigBuilder.OutboundMode.RULE,
        val bypassLan: Boolean = true,
        val bypassCn: Boolean = true,
        val overseasProxy: Boolean = false,
        val fallbackDirect: Boolean = false,
        val adBlock: Boolean = true,
        val mixedPort: Int = 2080,
        // keep in sync with MihomoCore.API_PORT (19090 — never clash's 9090 default)
        val apiPort: Int = 19090,
        val apiSecret: String = "",
    )

    // ---- clash / mihomo (line-based) ----

    fun applyClash(raw: String, options: Options): String {
        val lines = raw.lines().toMutableList()
        stripTunBlock(lines)

        setTopLevel(lines, "mixed-port", "${options.mixedPort}")
        setTopLevel(lines, "external-controller", quoteYaml("127.0.0.1:${options.apiPort}"))
        setTopLevel(lines, "secret", quoteYaml(options.apiSecret))
        setTopLevel(lines, "allow-lan", "false")
        setTopLevel(lines, "mode", modeOf(options.mode))
        if (!hasTopLevel(lines, "profile")) {
            lines.add("profile:")
            lines.add("  store-selected: true")
        }

        val proxyTarget = firstNameInBlock(lines, "proxy-groups") ?: firstNameInBlock(lines, "proxies")
        val injected = buildList {
            if (options.bypassLan) {
                add("IP-CIDR,10.0.0.0/8,DIRECT,no-resolve")
                add("IP-CIDR,172.16.0.0/12,DIRECT,no-resolve")
                add("IP-CIDR,192.168.0.0/16,DIRECT,no-resolve")
                add("IP-CIDR6,fc00::/7,DIRECT,no-resolve")
            }
            if (options.adBlock) add("GEOSITE,category-ads-all,REJECT")
            if (options.overseasProxy && proxyTarget != null) {
                add("GEOSITE,geolocation-!cn,$proxyTarget")
            }
            if (options.bypassCn) {
                add("GEOSITE,cn,DIRECT")
                add("GEOIP,cn,DIRECT,no-resolve")
            }
        }
        prependRules(lines, injected)
        if (options.fallbackDirect) insertFinalMatchDirect(lines)
        return lines.joinToString("\n") + "\n"
    }

    /** Same removal MihomoCore.stripTun does for generated configs. */
    private fun stripTunBlock(lines: MutableList<String>) {
        val start = lines.indexOfFirst { it.startsWith("tun:") }
        if (start < 0) return
        var end = lines.size
        for (i in start + 1 until lines.size) {
            if (lines[i].isNotBlank() && !lines[i].startsWith("  ")) {
                end = i
                break
            }
        }
        lines.subList(start, end).clear()
    }

    private fun modeOf(mode: ConfigBuilder.OutboundMode) = when (mode) {
        ConfigBuilder.OutboundMode.RULE -> "rule"
        ConfigBuilder.OutboundMode.GLOBAL -> "global"
        ConfigBuilder.OutboundMode.DIRECT -> "direct"
    }

    /** Replace a top-level scalar key, or append it at the end when missing. */
    private fun setTopLevel(lines: MutableList<String>, key: String, value: String) {
        val idx = lines.indexOfFirst { it.startsWith("$key:") && !it.startsWith(" ") }
        if (idx >= 0) {
            lines[idx] = "$key: $value"
        } else {
            lines.add("$key: $value")
        }
    }

    private fun hasTopLevel(lines: List<String>, key: String): Boolean =
        lines.any { it.startsWith("$key:") && !it.startsWith(" ") }

    /**
     * First `name:` of the first item inside a top-level block (proxy-groups /
     * proxies) — the stand-in target for the overseas rule. Comment lines do
     * not close the block.
     */
    private fun firstNameInBlock(lines: List<String>, blockKey: String): String? {
        var inBlock = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) continue
            if (!line.startsWith(" ") && !line.startsWith("-")) {
                inBlock = line.startsWith("$blockKey:")
                continue
            }
            if (!inBlock) continue
            val m = Regex("^\\s*-\\s*name:\\s*(.+?)\\s*(#.*)?$").find(line) ?: continue
            return unquoteYaml(m.groupValues[1])
        }
        return null
    }

    /** Insert rules at the head of the `rules:` list, matching its indentation. */
    private fun prependRules(lines: MutableList<String>, rules: List<String>) {
        if (rules.isEmpty()) return
        val idx = lines.indexOfFirst { it.startsWith("rules:") && !it.startsWith(" ") }
        if (idx < 0) {
            lines.add("rules:")
            rules.forEach { lines.add("  - $it") }
            return
        }
        // normalize "rules: []" / trailing comments so items can follow
        lines[idx] = "rules:"
        val indent = lines.getOrNull(idx + 1)
            ?.let { Regex("^(\\s*)-").find(it)?.groupValues?.get(1) }
            ?: "  "
        lines.addAll(idx + 1, rules.map { "$indent- $it" })
    }

    /** Fallback-direct: our MATCH,DIRECT must sit right before the original final MATCH. */
    private fun insertFinalMatchDirect(lines: MutableList<String>) {
        val lastMatch = lines.indexOfLast { it.trimStart().startsWith("- MATCH,") }
        if (lastMatch >= 0) {
            val indent = Regex("^(\\s*)").find(lines[lastMatch])?.groupValues?.get(1) ?: "  "
            lines.add(lastMatch, "${indent}- MATCH,DIRECT")
        } else {
            val rulesIdx = lines.indexOfFirst { it.startsWith("rules:") && !it.startsWith(" ") }
            if (rulesIdx >= 0) {
                var end = lines.size
                for (i in rulesIdx + 1 until lines.size) {
                    if (lines[i].isNotBlank() && !lines[i].startsWith(" ")) {
                        end = i
                        break
                    }
                }
                lines.add(end.coerceAtMost(lines.size), "  - MATCH,DIRECT")
            } else {
                lines.add("rules:")
                lines.add("  - MATCH,DIRECT")
            }
        }
    }

    private fun quoteYaml(s: String): String = "'" + s.replace("'", "''") + "'"

    private fun unquoteYaml(s: String): String = s.trim().let {
        when {
            it.length >= 2 && it.startsWith("'") && it.endsWith("'") -> it.substring(1, it.length - 1)
            it.length >= 2 && it.startsWith("\"") && it.endsWith("\"") -> it.substring(1, it.length - 1)
            else -> it
        }
    }

    // ---- sing-box (kotlinx JSON) ----

    private val json = Json { prettyPrint = true }

    fun applySingbox(raw: String, options: Options): String {
        val root = Json.parseToJsonElement(raw).jsonObject.toMutableMap()

        // outbounds: locate (or append) the direct / block targets and the
        // first selector/urltest group as the overseas proxy target
        val outbounds = (root["outbounds"] as? JsonArray ?: JsonArray(emptyList())).toMutableList()
        var directTag = outbounds.firstOrNull {
            (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "direct"
        }?.let { (it as JsonObject)["tag"]?.jsonPrimitive?.content }
        if (directTag == null) {
            directTag = "__interstellar_direct"
            outbounds.add(buildJsonObject { put("type", "direct"); put("tag", directTag) })
        }
        var blockTag = outbounds.firstOrNull {
            (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "block"
        }?.let { (it as JsonObject)["tag"]?.jsonPrimitive?.content }
        if (blockTag == null) {
            blockTag = "__interstellar_block"
            outbounds.add(buildJsonObject { put("type", "block"); put("tag", blockTag) })
        }
        val proxyTag = outbounds.firstOrNull {
            val type = (it as? JsonObject)?.get("type")?.jsonPrimitive?.content
            type == "selector" || type == "urltest"
        }?.let { (it as JsonObject)["tag"]?.jsonPrimitive?.content }
        root["outbounds"] = JsonArray(outbounds)

        // inbounds: keep the original tun (forcing auto_route — the app's VPN
        // model depends on it), add one when missing, same for the mixed port
        val inbounds = (root["inbounds"] as? JsonArray ?: JsonArray(emptyList())).toMutableList()
        val tunIdx = inbounds.indexOfFirst { (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "tun" }
        if (tunIdx >= 0) {
            val tun = (inbounds[tunIdx] as JsonObject).toMutableMap()
            tun["auto_route"] = kotlinx.serialization.json.JsonPrimitive(true)
            inbounds[tunIdx] = JsonObject(tun)
        } else {
            inbounds.add(
                buildJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    putJsonArray("address") { add("172.19.0.1/30") }
                    put("mtu", 9000)
                    put("auto_route", true)
                    put("stack", "mixed")
                    if (options.bypassLan) {
                        putJsonArray("route_exclude_address") {
                            add("10.0.0.0/8"); add("172.16.0.0/12"); add("192.168.0.0/16")
                        }
                    }
                },
            )
        }
        val hasMixed = inbounds.any {
            val obj = it as? JsonObject ?: return@any false
            obj["type"]?.jsonPrimitive?.content == "mixed" &&
                obj["listen_port"]?.jsonPrimitive?.content?.toIntOrNull() == options.mixedPort
        }
        if (!hasMixed) {
            inbounds.add(
                buildJsonObject {
                    put("type", "mixed")
                    put("tag", "mixed-in")
                    put("listen", "127.0.0.1")
                    put("listen_port", options.mixedPort)
                },
            )
        }
        root["inbounds"] = JsonArray(inbounds)

        // clash_api: the command socket + mode switching depend on it
        val experimental = (root["experimental"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        experimental["clash_api"] = buildJsonObject {
            put("external_controller", "127.0.0.1:${options.apiPort}")
            if (options.apiSecret.isNotBlank()) put("secret", options.apiSecret)
            put("default_mode", modeOf(options.mode))
        }
        root["experimental"] = JsonObject(experimental)

        // route: prepend the enabled built-in rules + their rule-set declarations
        val route = (root["route"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val rules = (route["rules"] as? JsonArray ?: JsonArray(emptyList())).toMutableList()
        val usedSets = linkedMapOf<String, RulesStore.RuleAsset>()
        val prepend = buildList<JsonElement> {
            if (options.bypassLan) {
                add(buildJsonObject { put("ip_is_private", true); put("outbound", directTag) })
            }
            if (options.adBlock) {
                usedSets["category-ads-all"] = RulesStore.adsAll
                add(buildJsonObject { putJsonArray("rule_set") { add("category-ads-all") }; put("outbound", blockTag) })
            }
            if (options.overseasProxy && proxyTag != null) {
                usedSets["geosite-geolocation-!cn"] = RulesStore.geolocationNotCn
                add(
                    buildJsonObject {
                        putJsonArray("rule_set") { add("geosite-geolocation-!cn") }
                        put("outbound", proxyTag)
                    },
                )
            }
            if (options.bypassCn) {
                usedSets["geosite-cn"] = RulesStore.geositeCn
                usedSets["geoip-cn"] = RulesStore.geoipCn
                add(
                    buildJsonObject {
                        putJsonArray("rule_set") { add("geosite-cn"); add("geoip-cn") }
                        put("outbound", directTag)
                    },
                )
            }
        }
        rules.addAll(0, prepend)
        route["rules"] = JsonArray(rules)
        val declared = (route["rule_set"] as? JsonArray ?: JsonArray(emptyList()))
            .mapNotNull { (it as? JsonObject)?.get("tag")?.jsonPrimitive?.content }
            .toSet()
        val sets = (route["rule_set"] as? JsonArray ?: JsonArray(emptyList())).toMutableList()
        for ((tag, asset) in usedSets) {
            if (tag !in declared) sets.add(ConfigBuilder.ruleSetJson(tag, asset))
        }
        route["rule_set"] = JsonArray(sets)
        if (options.fallbackDirect) route["final"] = kotlinx.serialization.json.JsonPrimitive(directTag)
        root["route"] = JsonObject(route)

        return json.encodeToString(JsonObject.serializer(), JsonObject(root))
    }

    // ---- Xray (org.json) ----

    fun applyXray(raw: String, options: Options): String {
        val root = JSONObject(raw)

        // the hev TUN bridge only ever speaks socks to 127.0.0.1:<port> —
        // original inbounds cannot coexist, so they are replaced wholesale
        root.put(
            "inbounds",
            JSONArray().put(
                JSONObject()
                    .put("tag", "socks-in")
                    .put("listen", "127.0.0.1")
                    .put("port", options.mixedPort)
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
                    .put(
                        "sniffing",
                        JSONObject()
                            .put("enabled", true)
                            .put("destOverride", JSONArray(listOf("http", "tls", "quic"))),
                    ),
            ),
        )

        val outbounds = root.optJSONArray("outbounds") ?: JSONArray()
        var directTag: String? = null
        var blockTag: String? = null
        var proxyTag: String? = null
        var hasDnsOut = false
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            val tag = ob.optString("tag")
            when (ob.optString("protocol")) {
                "freedom" -> if (directTag == null) directTag = tag.ifBlank { null }
                "blackhole" -> if (blockTag == null) blockTag = tag.ifBlank { null }
                "dns" -> hasDnsOut = true
                else -> if (proxyTag == null) proxyTag = tag.ifBlank { null }
            }
        }
        if (directTag == null) {
            directTag = "__interstellar_direct"
            outbounds.put(JSONObject().put("tag", directTag).put("protocol", "freedom"))
        }
        if (blockTag == null) {
            blockTag = "__interstellar_block"
            outbounds.put(JSONObject().put("tag", blockTag).put("protocol", "blackhole"))
        }
        if (!hasDnsOut) {
            outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        }
        root.put("outbounds", outbounds)

        val routing = root.optJSONObject("routing") ?: JSONObject()
        val rules = routing.optJSONArray("rules") ?: JSONArray()
        val dnsHijack = JSONObject().put("type", "field")
            .put("inboundTag", JSONArray(listOf("socks-in")))
            .put("port", 53)
            .put("network", "udp,tcp")
            .put("outboundTag", "dns-out")
        val prepend = mutableListOf(dnsHijack)
        if (options.bypassLan) {
            prepend.add(
                JSONObject().put("type", "field")
                    .put("ip", JSONArray(listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7")))
                    .put("outboundTag", directTag),
            )
        }
        if (options.adBlock) {
            prepend.add(
                JSONObject().put("type", "field")
                    .put("domain", JSONArray(listOf("geosite:category-ads-all")))
                    .put("outboundTag", blockTag),
            )
        }
        if (options.overseasProxy && proxyTag != null) {
            prepend.add(
                JSONObject().put("type", "field")
                    .put("domain", JSONArray(listOf("geosite:geolocation-!cn")))
                    .put("outboundTag", proxyTag),
            )
        }
        if (options.bypassCn) {
            prepend.add(
                JSONObject().put("type", "field")
                    .put("domain", JSONArray(listOf("geosite:cn")))
                    .put("outboundTag", directTag),
            )
            prepend.add(
                JSONObject().put("type", "field")
                    .put("ip", JSONArray(listOf("geoip:cn")))
                    .put("outboundTag", directTag),
            )
        }
        val merged = JSONArray()
        prepend.forEach(merged::put)
        for (i in 0 until rules.length()) merged.put(rules.get(i))
        if (options.fallbackDirect) {
            // after the original rules: unmatched traffic would fall out to
            // outbounds[0]; make the fallback explicit instead
            merged.put(
                JSONObject().put("type", "field")
                    .put("network", "tcp,udp")
                    .put("outboundTag", directTag),
            )
        }
        routing.put("rules", merged)
        root.put("routing", routing)

        return root.toString(2)
    }
}
