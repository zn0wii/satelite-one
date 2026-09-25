package com.interstellar.proxy.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DomainMatchType {
    @SerialName("domain")
    DOMAIN,

    @SerialName("domain_suffix")
    DOMAIN_SUFFIX,

    @SerialName("domain_keyword")
    DOMAIN_KEYWORD,
}

@Serializable
enum class NodeFilterMode {
    /** Matched domains bypass the proxy entirely (direct outbound). */
    @SerialName("direct")
    DIRECT,

    @SerialName("include")
    INCLUDE,

    @SerialName("exclude")
    EXCLUDE,
}

/**
 * User-defined split: match traffic by domain, then send it through a
 * urltest of nodes filtered by name keywords (include / exclude).
 */
@Serializable
data class CustomRouteRule(
    val id: String,
    val enabled: Boolean = true,
    val name: String = "",
    val matchType: DomainMatchType = DomainMatchType.DOMAIN_SUFFIX,
    val matchValue: String = "",
    val filterMode: NodeFilterMode = NodeFilterMode.EXCLUDE,
    val nodeKeywords: List<String> = emptyList(),
) {
    fun displayName(): String = name.trim().ifBlank { matchValue.trim() }

    fun parsedMatchValues(): List<String> = parseMatchValues(matchValue)
}

fun parseMatchValues(raw: String): List<String> =
    raw.split(',', '\n', ';')
        .map { sanitizeMatchValue(it) }
        .filter { it.isNotBlank() }
        .distinct()

fun sanitizeMatchValue(raw: String): String {
    var s = raw.trim()
    s = s.removePrefix("https://").removePrefix("http://")
    s = s.substringBefore('/').substringBefore('?')
    // drop a trailing port if present ("chatgpt.com:443")
    val colon = s.lastIndexOf(':')
    if (colon > 0 && s.substring(colon + 1).all { it.isDigit() }) {
        s = s.substring(0, colon)
    }
    return s.trim().trim('.')
}
