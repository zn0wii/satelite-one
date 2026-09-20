package com.interstellar.proxy.ui.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.BuildConfig
import com.interstellar.proxy.data.CustomRulesStore
import com.interstellar.proxy.data.DnsOverridesStore
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.ui.components.GlassCard
import com.interstellar.proxy.ui.components.IosSectionFooter
import com.interstellar.proxy.ui.components.IosSwitch
import com.interstellar.proxy.ui.components.PageHeader
import com.interstellar.proxy.ui.components.SegmentedControl
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.Accents
import com.interstellar.proxy.ui.theme.LocalInterstellarColors

private var onThemeChanged: (() -> Unit)? = null

/** Registered by MainActivity so setting changes re-compose the theme. */
fun setThemeChangedListener(listener: () -> Unit) {
    onThemeChanged = listener
}

enum class SettingsSubPage { Settings, PerApp, Connections, Logs, Rules, Dns, Proxy }

/** Bottom-dock root tabs (satelite's navbar, phone layout). */
enum class MainTab { Home, Nodes, Subscriptions, Logs, Settings }

/** hiddify-style: phone uses 2 tabs (Home/Settings); these pages push in. */
fun settingsSubPageTitle(page: SettingsSubPage): String = when (page) {
    SettingsSubPage.Settings -> "设置"
    SettingsSubPage.PerApp -> "分应用代理"
    SettingsSubPage.Connections -> "监控"
    SettingsSubPage.Logs -> "系统日志"
    SettingsSubPage.Rules -> "路由规则"
    SettingsSubPage.Dns -> "DNS 解析"
    SettingsSubPage.Proxy -> "路由设置"
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean =
    (context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)
        ?.isIgnoringBatteryOptimizations(context.packageName) ?: false

/**
 * Settings per the reference design: uppercase kicker + big title,
 * sectioned glass cards with plain title/description rows (no icon
 * squares, no separators) and right-aligned controls.
 */
@Composable
fun SettingsPage(onOpen: (SettingsSubPage) -> Unit, onProxyChanged: () -> Unit = {}) {
    val colors = LocalInterstellarColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var themeMode by remember { mutableStateOf(Settings.themeMode) }

    fun normalizedTheme(): String = when (themeMode) {
        "aerospace" -> "dark"
        "day" -> "light"
        else -> themeMode
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        PageHeader(kicker = "PREFERENCES", title = "设置")

        // ---- 外观 ----
        PrefSectionLabel("外观")
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            var heroStyle by remember { mutableStateOf(Settings.heroStyle) }
            PrefSegRow(
                title = "主题",
                desc = "深浅色跟随系统或锁定",
                items = listOf("跟随系统", "浅色", "深色"),
                selected = listOf("system", "light", "dark").indexOf(normalizedTheme()),
                layout = SegLayout.Below,
                onSelect = { index ->
                    val mode = listOf("system", "light", "dark")[index]
                    themeMode = mode
                    Settings.themeMode = mode
                    onThemeChanged?.invoke()
                },
            )
            PrefSegRow(
                title = "主视觉",
                desc = "首页连接图标的样式",
                items = listOf("笑脸", "轨道"),
                selected = if (heroStyle == "orbit") 1 else 0,
                layout = SegLayout.Trailing,
                onSelect = { index ->
                    heroStyle = if (index == 1) "orbit" else "smiley"
                    Settings.heroStyle = heroStyle
                },
            )
            PrefSwatchRow(
                title = "主题色",
                desc = "马卡龙色板,整套界面随之换肤",
            )
        }

        Spacer(Modifier.height(22.dp))

        // ---- 分流 ----
        PrefSectionLabel("分流")
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            PrefNavRow(
                title = "应用分流",
                desc = "白名单 / 黑名单控制哪些应用走代理",
                value = when {
                    !Settings.perAppProxyEnabled -> "关闭"
                    Settings.perAppProxyMode == Settings.PER_APP_PROXY_INCLUDE -> "白名单 · ${Settings.perAppProxyList.size}"
                    else -> "黑名单 · ${Settings.perAppProxyList.size}"
                },
                onClick = { onOpen(SettingsSubPage.PerApp) },
            )
            PrefNavRow(
                title = "路由设置",
                desc = "路由模式 / 规则细则 / 路由规则",
                value = when (Settings.outboundMode) {
                    com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.GLOBAL -> "全局"
                    com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.DIRECT -> "直连"
                    else -> "规则"
                },
                onClick = { onOpen(SettingsSubPage.Proxy) },
            )
            val dnsTotal = DnsOverridesStore.entries.size
            val dnsOn = DnsOverridesStore.entries.count { it.enabled }
            PrefNavRow(
                title = "DNS 解析",
                desc = "域名 → IP 手动解析覆盖",
                value = when {
                    dnsTotal == 0 -> "未设置"
                    else -> "$dnsOn 条启用"
                },
                onClick = { onOpen(SettingsSubPage.Dns) },
            )
        }

        Spacer(Modifier.height(22.dp))

        // ---- 诊断 ----
        PrefSectionLabel("诊断")
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            PrefNavRow(
                title = "系统日志",
                desc = "内核实时日志流",
                onClick = { onOpen(SettingsSubPage.Logs) },
            )
        }

        Spacer(Modifier.height(22.dp))

        // ---- 关于 ----
        PrefSectionLabel("关于")
        // battery-exemption state refreshes when the system dialog / settings round-trips back
        var batteryIgnored by remember {
            mutableStateOf(isIgnoringBatteryOptimizations(context))
        }
        val lifecycleOwner = context as? androidx.activity.ComponentActivity
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    batteryIgnored = isIgnoringBatteryOptimizations(context)
                }
            }
            lifecycleOwner?.lifecycle?.addObserver(observer)
            onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
        }
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            PrefNavRow(
                title = "电池优化豁免",
                desc = if (batteryIgnored) {
                    "已加入系统白名单;厂商省电策略可点进应用设置的电池选项改为无限制"
                } else {
                    "点击在系统弹窗中允许后台运行"
                },
                value = if (batteryIgnored) "已豁免" else null,
                onClick = {
                    runCatching {
                        val packageUri = android.net.Uri.parse("package:" + context.packageName)
                        context.startActivity(
                            // already whitelisted → the request intent is a no-op on
                            // most ROMs, so route to the app details page instead
                            if (batteryIgnored) {
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    packageUri,
                                )
                            } else {
                                android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    packageUri,
                                )
                            },
                        )
                    }
                },
            )
            PrefNavRow(title = "版本", value = BuildConfig.VERSION_NAME)
            PrefNavRow(title = "内核", value = "sing-box 1.14.0")
        }

        Spacer(Modifier.height(20.dp))
    }
}

/** Uppercase wide-tracked section label, reference style. */
@Composable
private fun PrefSectionLabel(text: String) {
    val colors = LocalInterstellarColors.current
    Text(
        text,
        color = colors.textTertiary,
        fontSize = 11.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
    )
}

@Composable
private fun PrefRowShell(
    title: String,
    desc: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressableClick(onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    desc,
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun PrefToggleRow(
    title: String,
    desc: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    PrefRowShell(title = title, desc = desc) {
        IosSwitch(checked = checked, onChange = onChange)
    }
}

private enum class SegLayout { Trailing, Below }

@Composable
private fun PrefSegRow(
    title: String,
    desc: String? = null,
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    layout: SegLayout = SegLayout.Trailing,
) {
    when (layout) {
        SegLayout.Trailing -> PrefRowShell(title = title, desc = desc) {
            SegmentedControl(
                items = items,
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.width(if (items.size >= 3) 190.dp else 128.dp),
            )
        }

        SegLayout.Below -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        color = LocalInterstellarColors.current.text,
                        fontSize = 15.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            SegmentedControl(
                items = items,
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PrefNavRow(
    title: String,
    desc: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalInterstellarColors.current
    PrefRowShell(title = title, desc = desc, onClick = onClick) {
        if (value != null) {
            Text(
                value,
                color = colors.textTertiary,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text("›", color = colors.textTertiary, fontSize = 20.sp)
    }
}

/** Macaron accent dots; the selected one grows and gains a ring. */
@Composable
private fun PrefSwatchRow(title: String, desc: String) {
    val colors = LocalInterstellarColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            title,
            color = colors.text,
            fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(desc, color = colors.textTertiary, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Accents.presets.forEach { preset ->
                AccentDot(
                    preset = preset,
                    selected = Accents.selectedId == preset.id,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AccentDot(preset: Accents.Preset, selected: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalInterstellarColors.current
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f),
        label = "accentDotScale",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(160),
        label = "accentDotRing",
    )
    val light = 0.2126f * colors.bg.red + 0.7152f * colors.bg.green + 0.0722f * colors.bg.blue > 0.5f
    Box(
        modifier = modifier
            .pressableClick { Accents.select(preset.id) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .graphicsLayer { alpha = ringAlpha }
                        .clip(CircleShape)
                        .border(1.6.dp, colors.textTertiary, CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(CircleShape)
                        .background(if (light) preset.light else preset.dark),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                preset.label,
                color = if (selected) colors.text else colors.textTertiary,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * 分流专用设置页: 路由模式 + 应用分流(白/黑名单) + 规则细则 + 规则入口。
 * 首页的状态行跳到这里做实际修改(首页只报状态)。
 */
@Composable
fun ProxySettingsPage(viewModel: com.interstellar.proxy.ui.AppViewModel, onOpen: (SettingsSubPage) -> Unit) {
    val colors = LocalInterstellarColors.current
    val routingMode by viewModel.routingMode.collectAsState()
    val proxyScope by viewModel.proxyScope.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(10.dp))

        // ---- 路由模式 ----
        PrefSectionLabel("路由模式")
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            val modes = listOf("rule" to "规则", "global" to "全局", "direct" to "直连")
            PrefSegRow(
                title = "模式",
                desc = "被代理流量的目的地走向",
                items = modes.map { it.second },
                selected = modes.indexOfFirst { it.first == routingMode }.coerceAtLeast(0),
                layout = SegLayout.Below,
                onSelect = { i -> viewModel.setClashMode(modes[i].first) },
            )
        }
        IosSectionFooter("规则模式按目的地规则(大陆/局域网/自定义)分流;全局模式全部经节点;直连保持 VPN 但不代理。")

        Spacer(Modifier.height(22.dp))

        // ---- 规则细则 ----
        PrefSectionLabel("规则细则")
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            var bypassLan by remember { mutableStateOf(Settings.bypassLanEnabled) }
            var bypassCn by remember { mutableStateOf(Settings.bypassCnEnabled) }
            var overseasProxy by remember { mutableStateOf(Settings.overseasProxyEnabled) }
            var fallbackDirect by remember { mutableStateOf(Settings.fallbackDirectEnabled) }
            var adBlock by remember { mutableStateOf(Settings.adBlockEnabled) }
            var regionGroups by remember { mutableStateOf(Settings.regionGroupsEnabled) }
            PrefToggleRow(
                title = "绕过局域网",
                desc = "访问 NAS、打印机、路由器不走代理",
                checked = bypassLan,
                onChange = {
                    bypassLan = it
                    Settings.bypassLanEnabled = it
                    viewModel.refreshProxyConfig()
                },
            )
            PrefToggleRow(
                title = "绕过大陆网站",
                desc = "大陆域名与 IP 直连不走代理(仅规则模式)",
                checked = bypassCn,
                onChange = {
                    bypassCn = it
                    Settings.bypassCnEnabled = it
                    viewModel.refreshProxyConfig()
                },
            )
            PrefToggleRow(
                title = "海外网站走代理",
                desc = "非大陆域名走代理(geolocation-!cn,仅规则模式)",
                checked = overseasProxy,
                onChange = {
                    overseasProxy = it
                    Settings.overseasProxyEnabled = it
                    viewModel.refreshProxyConfig()
                },
            )
            PrefSegRow(
                title = "未命中规则",
                desc = "未被任何规则匹配的流量走向(仅规则模式)",
                items = listOf("代理", "直连"),
                selected = if (fallbackDirect) 1 else 0,
                onSelect = { i ->
                    fallbackDirect = i == 1
                    Settings.fallbackDirectEnabled = i == 1
                    viewModel.refreshProxyConfig()
                },
            )
            PrefToggleRow(
                title = "去广告",
                desc = "拦截广告与跟踪域名",
                checked = adBlock,
                onChange = {
                    adBlock = it
                    Settings.adBlockEnabled = it
                    viewModel.refreshProxyConfig()
                },
            )
            PrefToggleRow(
                title = "按国家分组",
                desc = "节点页提供香港、新加坡等国家测速组",
                checked = regionGroups,
                onChange = {
                    regionGroups = it
                    Settings.regionGroupsEnabled = it
                    viewModel.refreshProxyConfig()
                },
            )
        }
        IosSectionFooter("海外走代理 + 兜底直连 = 白名单模式:只有命中规则的域名走代理,其余直连。")

        Spacer(Modifier.height(22.dp))

        // ---- 规则入口 ----
        PrefSectionLabel("路由规则")
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            val ruleOn = com.interstellar.proxy.data.SimpleRulesStore.rules.count { it.enabled }
            PrefNavRow(
                title = "路由规则",
                desc = "域名 → 直连 / 代理 / 指定节点",
                value = if (ruleOn == 0) "未设置" else "$ruleOn 条启用",
                onClick = { onOpen(SettingsSubPage.Rules) },
            )
        }
        IosSectionFooter("手动规则优先级最高,先于大陆绕过等内置规则匹配。")

        Spacer(Modifier.height(22.dp))

        // ---- 规则文件 ----
        PrefSectionLabel("规则文件")
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            val updating by viewModel.ruleFilesUpdating.collectAsState()
            PrefRowShell(
                title = "更新规则文件",
                desc = when (Settings.coreKind) {
                    com.interstellar.proxy.core.CoreKind.MIHOMO ->
                        "GEO 数据库 geosite + geoip(${Settings.coreKind.displayName},约 12 MB)"
                    com.interstellar.proxy.core.CoreKind.XRAY ->
                        "GEO 数据库 geosite + geoip(${Settings.coreKind.displayName},约 25 MB)"
                    else -> "内置规则集 srs(${Settings.coreKind.displayName},约 2 MB)"
                },
            ) {
                if (updating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = colors.primary,
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.primary.copy(alpha = 0.13f))
                            .border(1.dp, colors.primary.copy(alpha = 0.55f), RoundedCornerShape(50))
                            .pressableClick { viewModel.updateRuleFiles() }
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                    ) {
                        Text(
                            "更新",
                            color = colors.primary,
                            fontSize = 13.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        val updatedAt = Settings.ruleFilesUpdatedAt
        IosSectionFooter(
            "从 GitHub 下载最新规则,内核运行时经当前节点下载。" +
                if (updatedAt > 0) {
                    "上次更新:" + java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm",
                        java.util.Locale.getDefault(),
                    ).format(java.util.Date(updatedAt))
                } else {
                    "尚未更新过"
                },
        )

        Spacer(Modifier.height(24.dp))
    }
}
