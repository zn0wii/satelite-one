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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.BuildConfig
import com.interstellar.proxy.R
import com.interstellar.proxy.data.CustomRulesStore
import com.interstellar.proxy.data.DnsOverridesStore
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.data.net.AppUpdateChecker
import com.interstellar.proxy.ui.components.GlassCard
import com.interstellar.proxy.ui.components.IosSectionFooter
import com.interstellar.proxy.ui.components.IosSwitch
import com.interstellar.proxy.ui.components.PageHeader
import com.interstellar.proxy.ui.components.SegmentedControl
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.Accents
import com.interstellar.proxy.ui.theme.LocalInterstellarColors
import kotlinx.coroutines.launch

private var onThemeChanged: (() -> Unit)? = null

/** Registered by MainActivity so setting changes re-compose the theme. */
fun setThemeChangedListener(listener: () -> Unit) {
    onThemeChanged = listener
}

private var onLanguageChanged: (() -> Unit)? = null

/** Registered by MainActivity: swaps LocalContext (localized resources) in place. */
fun setLanguageChangedListener(listener: () -> Unit) {
    onLanguageChanged = listener
}

enum class SettingsSubPage { Settings, PerApp, Connections, Logs, Rules, Dns, Proxy }

/** Bottom-dock root tabs (satelite's navbar, phone layout). */
enum class MainTab { Home, Nodes, Subscriptions, Logs, Settings }

/** hiddify-style: phone uses 2 tabs (Home/Settings); these pages push in. */
@Composable
fun settingsSubPageTitle(page: SettingsSubPage): String = when (page) {
    SettingsSubPage.Settings -> stringResource(R.string.settings_title)
    SettingsSubPage.PerApp -> stringResource(R.string.settings_subpage_per_app)
    SettingsSubPage.Connections -> stringResource(R.string.settings_subpage_connections)
    SettingsSubPage.Logs -> stringResource(R.string.settings_subpage_logs)
    SettingsSubPage.Rules -> stringResource(R.string.settings_subpage_rules)
    SettingsSubPage.Dns -> stringResource(R.string.settings_subpage_dns)
    SettingsSubPage.Proxy -> stringResource(R.string.settings_subpage_proxy)
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
        PageHeader(kicker = "PREFERENCES", title = stringResource(R.string.settings_title))

        // ---- 外观 ----
        PrefSectionLabel(stringResource(R.string.settings_section_appearance))
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            var heroStyle by remember { mutableStateOf(Settings.heroStyle) }
            PrefSegRow(
                title = stringResource(R.string.settings_theme_title),
                desc = stringResource(R.string.settings_theme_desc),
                items = listOf(
                    stringResource(R.string.settings_theme_system),
                    stringResource(R.string.settings_theme_light),
                    stringResource(R.string.settings_theme_dark),
                ),
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
                title = stringResource(R.string.settings_hero_style_title),
                desc = stringResource(R.string.settings_hero_style_desc),
                items = listOf(
                    stringResource(R.string.settings_hero_smiley),
                    stringResource(R.string.settings_hero_orbit),
                ),
                selected = if (heroStyle == "orbit") 1 else 0,
                layout = SegLayout.Trailing,
                onSelect = { index ->
                    heroStyle = if (index == 1) "orbit" else "smiley"
                    Settings.heroStyle = heroStyle
                },
            )
            PrefSwatchRow(
                title = stringResource(R.string.settings_accent_title),
                desc = stringResource(R.string.settings_accent_desc),
            )
            var appLanguage by remember { mutableStateOf(Settings.appLanguage) }
            PrefSegRow(
                title = androidx.compose.ui.res.stringResource(R.string.settings_language_title),
                desc = androidx.compose.ui.res.stringResource(R.string.settings_language_desc),
                items = listOf(
                    androidx.compose.ui.res.stringResource(R.string.settings_language_system),
                    "中文",
                    "English",
                ),
                selected = listOf(
                    com.interstellar.proxy.ktx.AppLanguage.SYSTEM,
                    com.interstellar.proxy.ktx.AppLanguage.CHINESE,
                    com.interstellar.proxy.ktx.AppLanguage.ENGLISH,
                ).indexOf(appLanguage).coerceAtLeast(0),
                layout = SegLayout.Trailing,
                onSelect = { index ->
                    val value = listOf(
                        com.interstellar.proxy.ktx.AppLanguage.SYSTEM,
                        com.interstellar.proxy.ktx.AppLanguage.CHINESE,
                        com.interstellar.proxy.ktx.AppLanguage.ENGLISH,
                    )[index]
                    appLanguage = value
                    Settings.appLanguage = value
                    // swap LocalContext (localized resources) under the tree —
                    // in-place string swap, no activity recreate / flash
                    onLanguageChanged?.invoke()
                },
            )
        }

        Spacer(Modifier.height(22.dp))

        // ---- 分流 ----
        PrefSectionLabel(stringResource(R.string.settings_section_split))
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            PrefNavRow(
                title = stringResource(R.string.settings_per_app_title),
                desc = stringResource(R.string.settings_per_app_desc),
                value = when {
                    !Settings.perAppProxyEnabled -> stringResource(R.string.settings_per_app_disabled)
                    Settings.perAppProxyMode == Settings.PER_APP_PROXY_INCLUDE ->
                        stringResource(R.string.settings_per_app_whitelist_count, Settings.perAppProxyList.size)
                    else ->
                        stringResource(R.string.settings_per_app_blacklist_count, Settings.perAppProxyList.size)
                },
                onClick = { onOpen(SettingsSubPage.PerApp) },
            )
            PrefNavRow(
                title = stringResource(R.string.settings_subpage_proxy),
                desc = stringResource(R.string.settings_routing_desc),
                value = when (Settings.outboundMode) {
                    com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.GLOBAL ->
                        stringResource(R.string.settings_mode_global)
                    com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.DIRECT ->
                        stringResource(R.string.rule_action_direct)
                    else -> stringResource(R.string.settings_mode_rule)
                },
                onClick = { onOpen(SettingsSubPage.Proxy) },
            )
            val dnsTotal = DnsOverridesStore.entries.size
            val dnsOn = DnsOverridesStore.entries.count { it.enabled }
            PrefNavRow(
                title = stringResource(R.string.settings_subpage_dns),
                desc = stringResource(R.string.settings_dns_desc),
                value = when {
                    dnsTotal == 0 -> stringResource(R.string.settings_not_set)
                    else -> stringResource(R.string.settings_enabled_count, dnsOn)
                },
                onClick = { onOpen(SettingsSubPage.Dns) },
            )
        }

        Spacer(Modifier.height(22.dp))

        // ---- 诊断 ----
        PrefSectionLabel(stringResource(R.string.settings_section_diagnostics))
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            PrefNavRow(
                title = stringResource(R.string.settings_subpage_logs),
                desc = stringResource(R.string.settings_logs_desc),
                onClick = { onOpen(SettingsSubPage.Logs) },
            )
        }

        Spacer(Modifier.height(22.dp))

        // ---- 关于 ----
        PrefSectionLabel(stringResource(R.string.settings_section_about))
        // battery-exemption state refreshes when the system dialog / settings round-trips back
        // NB: LocalContext may be the locale wrapper — the real activity comes
        // from the view (whose context is always the hosting activity)
        val activity = androidx.compose.ui.platform.LocalView.current.context
        var batteryIgnored by remember {
            mutableStateOf(isIgnoringBatteryOptimizations(context))
        }
        val lifecycleOwner = activity as? androidx.activity.ComponentActivity
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
                title = stringResource(R.string.settings_battery_title),
                desc = if (batteryIgnored) {
                    stringResource(R.string.settings_battery_desc_ignored)
                } else {
                    stringResource(R.string.settings_battery_desc_request)
                },
                value = if (batteryIgnored) stringResource(R.string.settings_battery_exempted) else null,
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
            PrefNavRow(title = stringResource(R.string.settings_version_title), value = BuildConfig.VERSION_NAME)
            UpdateCheckRow()
            PrefNavRow(title = stringResource(R.string.settings_core_title), value = "sing-box 1.14.0")
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

/**
 * 检查更新 row: checks the GitHub release tag on demand; a newer tag turns
 * the row + trailing capsule into a jump to the releases page.
 */
@Composable
private fun UpdateCheckRow() {
    val colors = LocalInterstellarColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AppUpdateChecker.Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val networkErrorText = stringResource(R.string.settings_check_update_network_error)

    fun openReleases() {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(AppUpdateChecker.RELEASES_PAGE),
                ),
            )
        }
    }

    fun startCheck() {
        if (checking) return
        checking = true
        error = null
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            result = runCatching { AppUpdateChecker.check() }.getOrElse {
                error = it.message ?: networkErrorText
                null
            }
            checking = false
        }
    }

    PrefRowShell(
        title = stringResource(R.string.settings_check_update_title),
        desc = when (val r = result) {
            is AppUpdateChecker.Result.UpdateAvailable ->
                stringResource(R.string.settings_check_update_available, r.latestTag)

            is AppUpdateChecker.Result.UpToDate ->
                stringResource(R.string.settings_check_update_uptodate, r.currentTag)

            null -> if (error != null) {
                stringResource(R.string.settings_check_update_failed, error ?: "")
            } else {
                stringResource(R.string.settings_check_update_desc)
            }
        },
        onClick = if (result is AppUpdateChecker.Result.UpdateAvailable) {
            { openReleases() }
        } else {
            null
        },
    ) {
        when {
            checking -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = colors.primary,
            )

            result is AppUpdateChecker.Result.UpdateAvailable -> CapsuleAction(
                text = stringResource(R.string.settings_check_update_go),
                accent = colors.primary,
                onClick = { openReleases() },
            )

            else -> CapsuleAction(text = stringResource(R.string.settings_check_update_btn), accent = colors.text, onClick = { startCheck() })
        }
    }
}

/** Small capsule action chip for trailing slots (same style as 规则文件's 更新). */
@Composable
private fun CapsuleAction(text: String, accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.13f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(50))
            .pressableClick { onClick() }
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            color = accent,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
    }
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
                androidx.compose.ui.res.stringResource(preset.labelRes),
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
        PrefSectionLabel(stringResource(R.string.settings_routing_mode))
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            val modes = listOf(
                "rule" to stringResource(R.string.settings_mode_rule),
                "global" to stringResource(R.string.settings_mode_global),
                "direct" to stringResource(R.string.rule_action_direct),
            )
            PrefSegRow(
                title = stringResource(R.string.settings_mode_title),
                desc = stringResource(R.string.settings_mode_desc),
                items = modes.map { it.second },
                selected = modes.indexOfFirst { it.first == routingMode }.coerceAtLeast(0),
                layout = SegLayout.Below,
                onSelect = { i -> viewModel.setClashMode(modes[i].first) },
            )
        }
        IosSectionFooter(stringResource(R.string.settings_routing_mode_footer))

        Spacer(Modifier.height(22.dp))

        // ---- 规则细则 ----
        PrefSectionLabel(stringResource(R.string.settings_rule_details))
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            var bypassLan by remember { mutableStateOf(Settings.bypassLanEnabled) }
            var bypassCn by remember { mutableStateOf(Settings.bypassCnEnabled) }
            var overseasProxy by remember { mutableStateOf(Settings.overseasProxyEnabled) }
            var fallbackDirect by remember { mutableStateOf(Settings.fallbackDirectEnabled) }
            var adBlock by remember { mutableStateOf(Settings.adBlockEnabled) }
            var regionGroups by remember { mutableStateOf(Settings.regionGroupsEnabled) }
            PrefToggleRow(
                title = stringResource(R.string.settings_bypass_lan_title),
                desc = stringResource(R.string.settings_bypass_lan_desc),
                checked = bypassLan,
                onChange = {
                    bypassLan = it
                    Settings.bypassLanEnabled = it
                    viewModel.refreshProxyConfig()
                },
            )
            PrefToggleRow(
                title = stringResource(R.string.settings_bypass_cn_title),
                desc = stringResource(R.string.settings_bypass_cn_desc),
                checked = bypassCn,
                onChange = {
                    bypassCn = it
                    Settings.bypassCnEnabled = it
                    viewModel.refreshProxyConfig()
                },
            )
            PrefToggleRow(
                title = stringResource(R.string.settings_overseas_proxy_title),
                desc = stringResource(R.string.settings_overseas_proxy_desc),
                checked = overseasProxy,
                onChange = {
                    overseasProxy = it
                    Settings.overseasProxyEnabled = it
                    viewModel.refreshProxyConfig()
                },
            )
            PrefSegRow(
                title = stringResource(R.string.settings_fallback_title),
                desc = stringResource(R.string.settings_fallback_desc),
                items = listOf(
                    stringResource(R.string.rule_action_proxy),
                    stringResource(R.string.rule_action_direct),
                ),
                selected = if (fallbackDirect) 1 else 0,
                onSelect = { i ->
                    fallbackDirect = i == 1
                    Settings.fallbackDirectEnabled = i == 1
                    viewModel.refreshProxyConfig()
                },
            )
            PrefToggleRow(
                title = stringResource(R.string.settings_adblock_title),
                desc = stringResource(R.string.settings_adblock_desc),
                checked = adBlock,
                onChange = {
                    adBlock = it
                    Settings.adBlockEnabled = it
                    viewModel.refreshProxyConfig()
                },
            )
            PrefToggleRow(
                title = stringResource(R.string.settings_region_groups_title),
                desc = stringResource(R.string.settings_region_groups_desc),
                checked = regionGroups,
                onChange = {
                    regionGroups = it
                    Settings.regionGroupsEnabled = it
                    viewModel.refreshProxyConfig()
                },
            )
        }
        IosSectionFooter(stringResource(R.string.settings_whitelist_mode_footer))

        Spacer(Modifier.height(22.dp))

        // ---- 规则入口 ----
        PrefSectionLabel(stringResource(R.string.settings_subpage_rules))
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            val ruleOn = com.interstellar.proxy.data.SimpleRulesStore.rules.count { it.enabled }
            PrefNavRow(
                title = stringResource(R.string.settings_subpage_rules),
                desc = stringResource(R.string.settings_custom_rules_desc),
                value = if (ruleOn == 0) {
                    stringResource(R.string.settings_not_set)
                } else {
                    stringResource(R.string.settings_enabled_count, ruleOn)
                },
                onClick = { onOpen(SettingsSubPage.Rules) },
            )
        }
        IosSectionFooter(stringResource(R.string.settings_custom_rules_footer))

        Spacer(Modifier.height(22.dp))

        // ---- 规则文件 ----
        PrefSectionLabel(stringResource(R.string.settings_rule_files))
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            val updating by viewModel.ruleFilesUpdating.collectAsState()
            PrefRowShell(
                title = stringResource(R.string.settings_rule_files_update_title),
                desc = when (Settings.coreKind) {
                    com.interstellar.proxy.core.CoreKind.MIHOMO ->
                        stringResource(R.string.settings_rule_files_geo_mihomo, Settings.coreKind.displayName)
                    com.interstellar.proxy.core.CoreKind.XRAY ->
                        stringResource(R.string.settings_rule_files_geo_xray, Settings.coreKind.displayName)
                    else ->
                        stringResource(R.string.settings_rule_files_srs, Settings.coreKind.displayName)
                },
            ) {
                if (updating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = colors.primary,
                    )
                } else {
                    CapsuleAction(text = stringResource(R.string.settings_rule_files_update), accent = colors.primary) { viewModel.updateRuleFiles() }
                }
            }
        }
        val updatedAt = Settings.ruleFilesUpdatedAt
        IosSectionFooter(
            stringResource(R.string.settings_rule_files_footer) +
                if (updatedAt > 0) {
                    stringResource(
                        R.string.settings_rule_files_last_update,
                        java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm",
                            java.util.Locale.getDefault(),
                        ).format(java.util.Date(updatedAt)),
                    )
                } else {
                    stringResource(R.string.settings_rule_files_never_updated)
                },
        )

        Spacer(Modifier.height(24.dp))
    }
}
