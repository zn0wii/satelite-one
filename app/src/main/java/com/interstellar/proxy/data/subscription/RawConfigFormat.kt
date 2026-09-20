package com.interstellar.proxy.data.subscription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Detects whether a subscription body is a full proxy-core config (clash /
 * sing-box / Xray) rather than a plain node list. Raw configs can be kept on
 * disk and fed to the matching core verbatim (plus compatibility shims) —
 * see RawConfigApplier.
 */
enum class RawConfigFormat(val wire: String, val label: String) {
    CLASH("clash", "Clash"),
    SINGBOX("singbox", "sing-box"),
    XRAY("xray", "Xray");

    companion object {
        fun from(value: String?): RawConfigFormat? = value?.let { v ->
            entries.find { it.wire == v }
        }
    }
}

object RawConfigDetector {

    /**
     * A body is a raw config when it carries a full core structure, not just
     * nodes: clash needs `proxies:` (+ usually rules/proxy-groups), the JSON
     * cores need `outbounds` where sing-box items carry `type` and Xray items
     * carry `protocol`. Base64-wrapped bodies are decoded first.
     */
    fun detect(content: String): RawConfigFormat? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null

        val decoded = if (trimmed.startsWith("{")) {
            trimmed
        } else {
            SubscriptionParserDecoded.base64Decode(trimmed) ?: return detectNonJson(trimmed)
        }
        if (decoded.trimStart().startsWith("{")) {
            return detectJsonConfig(decoded)
        }
        return detectNonJson(decoded)
    }

    private fun detectNonJson(text: String): RawConfigFormat? =
        if (ClashParser.isClashConfig(text)) RawConfigFormat.CLASH else null

    private fun detectJsonConfig(text: String): RawConfigFormat? = runCatching {
        val json = Json.parseToJsonElement(text).jsonObject
        val outbounds = json["outbounds"]?.jsonArray ?: return@runCatching null
        if (outbounds.isEmpty()) return@runCatching null
        val objs = outbounds.filterIsInstance<JsonObject>()
        // sing-box outbounds carry "type"; xray outbounds carry "protocol"
        val withType = objs.count { "type" in it }
        val withProtocol = objs.count { "protocol" in it }
        when {
            withProtocol > 0 && withProtocol >= withType -> RawConfigFormat.XRAY
            withType > 0 -> RawConfigFormat.SINGBOX
            else -> null
        }
    }.getOrNull()
}

/** Base64 decode shared with SubscriptionParser's private logic (no pad/charset drift). */
internal object SubscriptionParserDecoded {
    fun base64Decode(text: String): String? {
        val cleaned = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("")
        if (cleaned.isEmpty()) return null
        // a plausible base64 body: only alphabet chars; anything else (yaml/uri) is not
        if (!cleaned.all { it.isLetterOrDigit() || it in "-_+/=" }) return null
        val normalized = cleaned.replace("-", "+").replace("_", "/")
        val padded = when (normalized.length % 4) {
            2 -> "$normalized=="
            3 -> "$normalized="
            else -> normalized
        }
        return runCatching {
            val bytes = java.util.Base64.getMimeDecoder().decode(padded)
            val decoded = String(bytes, Charsets.UTF_8)
            if (decoded.count { it.code in 32..126 || it == '\n' || it == '\r' } < decoded.length * 9 / 10) {
                null
            } else {
                decoded
            }
        }.getOrNull()
    }
}
