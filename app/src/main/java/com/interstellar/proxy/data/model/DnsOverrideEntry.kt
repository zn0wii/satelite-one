package com.interstellar.proxy.data.model

import kotlinx.serialization.Serializable

/**
 * User-defined DNS injection: resolve the listed domains to a fixed IP
 * (hosts semantics, exact match), overriding any upstream answer.
 */
@Serializable
data class DnsOverrideEntry(
    val id: String,
    val enabled: Boolean = true,
    /** Comma / newline separated domains. */
    val domains: String = "",
    val ip: String = "",
) {
    fun displayName(): String = domains.trim()

    fun parsedDomains(): List<String> = parseDnsDomains(domains)
}

fun parseDnsDomains(raw: String): List<String> =
    raw.split(',', '\n', ';')
        .map { sanitizeMatchValue(it).lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()

/**
 * Accepts IPv4 and IPv6 literals only — no hostname (would need a DNS
 * lookup) and no CIDR prefix. Charset-guarded before the literal parse
 * so InetAddress can never fall through to the resolver.
 */
fun isValidIpLiteral(value: String): Boolean {
    val v = value.trim()
    if (v.isEmpty()) return false
    Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(v)?.let { m ->
        return m.groupValues.drop(1).all { it.toInt() in 0..255 }
    }
    if (v.contains(':')) {
        if (v.any { it !in "0123456789abcdefABCDEF:." }) return false
        return runCatching {
            java.net.InetAddress.getByName(v) is java.net.Inet6Address
        }.getOrDefault(false)
    }
    return false
}
