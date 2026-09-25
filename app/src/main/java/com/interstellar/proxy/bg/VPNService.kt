package com.interstellar.proxy.bg

import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.interstellar.proxy.R
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.TunOptions
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.ktx.toIpPrefix
import com.interstellar.proxy.ktx.toList
import com.interstellar.proxy.ktx.wrapAppLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class VPNService :
    VpnService(),
    PlatformInterfaceWrapper {
    companion object {
        private const val TAG = "VPNService"
        private const val EXCLUSION_RESOLVE_BUDGET_MS = 3000L
    }

    private val service = BoxService(this, this)

    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base?.wrapAppLocale())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = service.onStartCommand()

    override fun onBind(intent: Intent): IBinder {
        val binder = super.onBind(intent)
        if (binder != null) {
            return binder
        }
        return service.onBind()
    }

    override fun onDestroy() {
        service.onDestroy()
    }

    override fun onRevoke() {
        runBlocking {
            withContext(Dispatchers.Main) {
                service.onRevoke()
            }
        }
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    var systemProxyAvailable = false
    var systemProxyEnabled = false

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        val builder =
            Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (Settings.allowBypass) {
            builder.allowBypass()
        }

        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dnsServerAddress = options.dnsServerAddress
                while (dnsServerAddress.hasNext()) {
                    builder.addDnsServer(dnsServerAddress.next())
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val inet4RouteAddress = options.inet4RouteAddress
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        builder.addRoute(inet4RouteAddress.next().toIpPrefix())
                    }
                } else if (options.inet4Address.hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }

                val inet6RouteAddress = options.inet6RouteAddress
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        builder.addRoute(inet6RouteAddress.next().toIpPrefix())
                    }
                } else if (options.inet6Address.hasNext()) {
                    builder.addRoute("::", 0)
                }

                val inet4RouteExcludeAddress = options.inet4RouteExcludeAddress
                while (inet4RouteExcludeAddress.hasNext()) {
                    builder.excludeRoute(inet4RouteExcludeAddress.next().toIpPrefix())
                }

                val inet6RouteExcludeAddress = options.inet6RouteExcludeAddress
                while (inet6RouteExcludeAddress.hasNext()) {
                    builder.excludeRoute(inet6RouteExcludeAddress.next().toIpPrefix())
                }
            } else {
                val inet4RouteAddress = options.inet4RouteRange
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        val address = inet4RouteAddress.next()
                        builder.addRoute(address.address(), address.prefix())
                    }
                }

                val inet6RouteAddress = options.inet6RouteRange
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        val address = inet6RouteAddress.next()
                        builder.addRoute(address.address(), address.prefix())
                    }
                }
            }

            val includePackage = options.includePackage
            if (includePackage.hasNext()) {
                while (includePackage.hasNext()) {
                    try {
                        val nextPackage = includePackage.next()
                        builder.addAllowedApplication(nextPackage)
                        Log.d(TAG, "addAllowedApplication: $nextPackage")
                    } catch (e: NameNotFoundException) {
                        Log.e(TAG, "addAllowedApplication failed", e)
                    }
                }
            }

            val excludePackage = options.excludePackage
            if (excludePackage.hasNext()) {
                while (excludePackage.hasNext()) {
                    try {
                        val nextPackage = excludePackage.next()
                        builder.addDisallowedApplication(nextPackage)
                        Log.d(TAG, "addDisallowedApplication: $nextPackage")
                    } catch (e: NameNotFoundException) {
                        Log.e(TAG, "addDisallowedApplication failed", e)
                    }
                }
            }
        }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemProxyAvailable = true
            systemProxyEnabled = Settings.systemProxyEnabled
            if (systemProxyEnabled) {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy(
                        options.httpProxyServer,
                        options.httpProxyServerPort,
                        options.httpProxyBypassDomain.toList(),
                    ),
                )
            }
        } else {
            systemProxyAvailable = false
            systemProxyEnabled = false
        }

        val pfd =
            builder.establish() ?: error("android: the application is not prepared or is revoked")
        service.fileDescriptor = pfd
        return pfd.fd
    }

    override fun sendNotification(notification: Notification) = service.sendNotification(notification)

    override fun cancelNotification(identifier: String, typeID: Int) = service.cancelNotification(identifier, typeID)

    // ---- sidecar-core tun (mihomo / Xray) ----

    /**
     * Establish the VPN for a sidecar core: the app (not the core) owns the
     * builder. Node server IPs are excluded from the routes so the core's
     * own outbound sockets bypass the tun (no protect() across processes).
     * The returned fd has CLOEXEC cleared so the exec'd core inherits it.
     */
    fun establishSidecarTun(spec: com.interstellar.proxy.core.SidecarTunSpec): Int? {
        val started = android.os.SystemClock.elapsedRealtime()
        com.interstellar.proxy.core.AppLog.log("vpn", "建立 sidecar TUN…")
        if (prepare(this) != null) {
            com.interstellar.proxy.core.AppLog.log("vpn", "VPN 未授权, 以纯代理模式启动")
            return null
        }

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(spec.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        if (spec.allowBypass) {
            builder.allowBypass()
        }
        // conventional tun addressing; the fd stays in-process (hev bridge)
        builder.addAddress("172.19.0.1", 30)
        // app DNS rides the tunnel (hev → socks → mihomo rules)
        builder.addDnsServer("1.1.1.1")

        val excluded = resolveExclusions(spec.exclusions)
        com.interstellar.proxy.core.AppLog.log(
            "vpn",
            "排除路由 ${excluded.size} 条 (解析耗时 ${android.os.SystemClock.elapsedRealtime() - started}ms)",
        )
        val hasModernExclusions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (hasModernExclusions) {
            builder.addRoute("0.0.0.0", 0)
            // v4-only sidecar VPN (no v6 address on the interface)
            for (prefix in excluded) {
                if (prefix.address.address.size == 4) {
                    runCatching { builder.excludeRoute(prefix) }
                }
            }
        } else {
            // pre-13: split the IPv4 space around the exclusions instead
            for (prefix in complementRoutes(excluded)) {
                runCatching { builder.addRoute(prefix) }
            }
        }

        // The sidecar core (mihomo / xray) runs proxy-only in a child process
        // that cannot call VpnService.protect() — if the app's own uid stays
        // inside the VPN, every core dial to a non-excluded address loops
        // back into the tun (hev → core → tun → …). DIRECT mode dials
        // arbitrary destinations, so node-IP exclusions alone can't prevent
        // that: the app itself must stay OUT of its own VPN.
        runCatching {
            if (spec.perAppEnabled && spec.perAppInclude) {
                // allow-list mode: self is simply not listed → outside the VPN
                spec.perAppPackages.forEach { builder.addAllowedApplication(it) }
            } else {
                spec.perAppPackages.forEach { builder.addDisallowedApplication(it) }
                builder.addDisallowedApplication(packageName)
            }
        }.onFailure { Log.e(TAG, "per-app vpn config failed", it) }

        val pfd = builder.establish() ?: return null
        service.fileDescriptor = pfd
        Log.i(TAG, "sidecar tun established fd=${pfd.fd}")
        com.interstellar.proxy.core.AppLog.log("vpn", "sidecar tun fd=${pfd.fd}")
        return pfd.fd
    }

    /** hosts/IPs → IpPrefixes; domains resolve in parallel under a hard budget. */
    private fun resolveExclusions(hosts: List<String>): List<android.net.IpPrefix> {
        val literals = mutableListOf<android.net.IpPrefix>()
        val domains = mutableListOf<String>()
        for (host in hosts.distinct()) {
            val ip = host.substringBefore('/').trim()
            if (ip.isEmpty()) continue
            // literal IPs parse without any DNS lookup
            val literal = runCatching {
                java.net.InetAddress.getByName(ip).takeIf {
                    it.hostAddress == ip && !it.isAnyLocalAddress() && !it.isLoopbackAddress()
                }
            }.getOrNull()
            if (literal != null) {
                literals.add(android.net.IpPrefix(literal, if (literal.address.size == 4) 32 else 128))
            } else {
                domains.add(ip)
            }
        }
        if (domains.isEmpty()) return literals
        // sequential getByName can take 5s per slow host — parallel + budget
        val resolved: List<java.net.InetAddress> = runBlocking {
            withTimeoutOrNull(EXCLUSION_RESOLVE_BUDGET_MS) {
                coroutineScope {
                    domains.map { d ->
                        async(Dispatchers.IO) {
                            runCatching { java.net.InetAddress.getAllByName(d).toList() }
                                .getOrDefault(emptyList())
                        }
                    }.awaitAll().flatten()
                }
            }
        }.orEmpty()
        return literals + resolved
            .filter { !it.isAnyLocalAddress() && !it.isLoopbackAddress() }
            .map { android.net.IpPrefix(it, if (it.address.size == 4) 32 else 128) }
            .distinct()
    }

    /** IPv4 full space minus exclusions as addRoute-able prefixes (API < 33). */
    private fun complementRoutes(excluded: List<android.net.IpPrefix>): List<android.net.IpPrefix> {
        var ranges = listOf(0L..(1L shl 32) - 1)
        for (prefix in excluded) {
            val r = toRange(prefix) ?: continue
            ranges = ranges.flatMap { existing ->
                when {
                    r.last < existing.first || r.first > existing.last -> listOf(existing)
                    r.first <= existing.first && r.last >= existing.last -> emptyList()
                    else -> buildList {
                        if (r.first > existing.first) add(existing.first until r.first)
                        if (r.last < existing.last) add((r.last + 1)..existing.last)
                    }
                }
            }
        }
        return ranges.flatMap { rangeToCidrs(it.first, it.last) }
    }

    private fun toRange(p: android.net.IpPrefix): LongRange? {
        val bytes = p.address.address
        if (bytes.size != 4) return null // v6 complement skipped on legacy devices
        var start = 0L
        bytes.forEach { start = (start shl 8) or (it.toLong() and 0xff) }
        val size = 1L shl (32 - p.prefixLength)
        return start until (start + size).coerceAtMost(1L shl 32)
    }

    private fun rangeToCidrs(start: Long, endInclusive: Long): List<android.net.IpPrefix> {
        var current = start
        val out = mutableListOf<android.net.IpPrefix>()
        while (current <= endInclusive && out.size < 1024) {
            var prefixLen = 32
            while (prefixLen > 0) {
                val size = 1L shl (32 - prefixLen)
                if (current % size == 0L && current + size - 1 <= endInclusive) break
                prefixLen--
            }
            val size = 1L shl (32 - prefixLen)
            val addr = java.net.InetAddress.getByAddress(
                byteArrayOf(
                    (current ushr 24).toByte(),
                    (current ushr 16).toByte(),
                    (current ushr 8).toByte(),
                    current.toByte(),
                ),
            )
            out.add(android.net.IpPrefix(addr, prefixLen))
            current += size
        }
        return out
    }
}
