package com.interstellar.proxy.ui.pages

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.R
import com.interstellar.proxy.constant.Status
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.data.SubscriptionRepository
import com.interstellar.proxy.data.config.ConfigBuilder
import com.interstellar.proxy.ui.AppViewModel
import com.interstellar.proxy.ui.ConnectionsViewModel
import com.interstellar.proxy.ui.ProbeState
import com.interstellar.proxy.ui.components.FaceMark
import com.interstellar.proxy.ui.components.GlassButton
import com.interstellar.proxy.ui.components.GlassButtonStyle
import com.interstellar.proxy.ui.components.GlassCard
import com.interstellar.proxy.ui.components.OrbitHero
import com.interstellar.proxy.ui.components.SegmentedControl
import com.interstellar.proxy.ui.components.StatusPill
import com.interstellar.proxy.ui.components.glassSurface
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.LocalInterstellarColors
import com.interstellar.proxy.ui.theme.Motion
import io.nekohasekai.libbox.Libbox
import com.interstellar.proxy.core.CoreGroup

private const val CORE_VERSION = "sing-box 1.14.0"
private const val MIHOMO_VERSION = "mihomo v1.19.30"
private const val XRAY_VERSION_CARD = "Xray v26.3.27"

@Composable
fun DashboardPage(
    viewModel: AppViewModel,
    connectionsViewModel: ConnectionsViewModel,
    onStart: () -> Unit = { viewModel.startProxy() },
    onOpenSubPage: (SettingsSubPage) -> Unit = {},
    onOpenTab: (MainTab) -> Unit = {},
) {
    val colors = LocalInterstellarColors.current
    val haptics = LocalHapticFeedback.current
    val status by viewModel.status.collectAsState()
    val speed by viewModel.speed.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val delays by viewModel.delays.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val storedSelected by viewModel.selectedOutboundTag.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val activeSubscriptionId by viewModel.activeSubscriptionId.collectAsState()
    val mixEnabled by viewModel.mixEnabled.collectAsState()
    val mixSubscriptionIds by viewModel.mixSubscriptionIds.collectAsState()
    val connectedAt by viewModel.connectedAt.collectAsState()
    val connections by connectionsViewModel.connections.collectAsState()
    val history by viewModel.history.collectAsState()
    val routingMode by viewModel.routingMode.collectAsState()
    val coreKind by viewModel.coreKind.collectAsState()
    val proxyScope by viewModel.proxyScope.collectAsState()
    val mihomoConnectionCount by viewModel.mihomoConnectionCount.collectAsState()
    val probe by viewModel.probe.collectAsState()
    val running = status == Status.Started
    val activeConnectionCount = connections.count { !it.closed }
    // raw configs may not name any group "proxy" — fall back to the first selector
    val mainGroup = groups.find { it.tag == ConfigBuilder.GROUP_TAG }
        ?: groups.firstOrNull { it.type.equals("selector", ignoreCase = true) }
        ?: groups.firstOrNull()
    // tag → protocol (VLESS / TROJAN / …) for the current node pool
    val protocolByTag = remember(subscriptions, activeSubscriptionId, mixEnabled, mixSubscriptionIds) {
        val pool = SubscriptionRepository.poolOf(
            subscriptions,
            activeSubscriptionId,
            mixEnabled,
            mixSubscriptionIds,
        )
        ConfigBuilder.tagsFor(pool).zip(pool)
            .associate { (tag, node) -> tag to node.type.wire.uppercase() }
    }

    // 左右滑动切换 dock tab 的手势由 MainActivity 在 tab 根页统一挂载
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        val heroSize = 196.dp.coerceAtMost(maxWidth - 140.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = viewportHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 顶栏：品牌 + 监控/设置 玻璃圆钮
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp, bottom = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.app_name),
                        color = colors.text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.app_tagline),
                        color = colors.textTertiary,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                    )
                }
                GlassIconButton(
                    icon = Icons.AutoMirrored.Outlined.TrendingUp,
                    contentDescription = "监控",
                ) { onOpenSubPage(SettingsSubPage.Connections) }
            }

            Spacer(Modifier.height(8.dp))

            // ── Hero：状态驱动的主视觉，点击连接/断开
            val heroStyle = Settings.heroStyle
            HeroButton(
                status = status,
                enabled = !busy,
                heroSize = heroSize,
                style = heroStyle,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (running) viewModel.stopProxy() else onStart()
                },
            )

            Spacer(Modifier.height(10.dp))

            // ── Kicker：状态胶囊居中（运行时长在下方核心卡片里，不在此重复）
            StatusPill(
                text = when (status) {
                    Status.Started -> "RUN"
                    Status.Starting -> "CONNECTING"
                    Status.Stopping -> "STOPPING"
                    Status.Stopped -> "OFF"
                },
                color = when (status) {
                    Status.Started -> colors.primary
                    Status.Starting, Status.Stopping -> colors.warning
                    Status.Stopped -> colors.textTertiary
                },
                active = running || status == Status.Starting,
            )

            Spacer(Modifier.height(10.dp))

            // ── 节点名（大字，自动缩放）：连接中显示实时出口，未连接显示下次将使用的节点
            val connected = status == Status.Started || status == Status.Starting
            val resolvedNode = nodeRowValue(groups, delays, mainGroup, storedSelected)
            val picking = connected && (resolvedNode == "自动" || resolvedNode == "未选择")
            // 选择中呼吸动画只在 picking 时运转,其余时间零帧开销
            val pickPulse = if (picking) {
                rememberInfiniteTransition(label = "pickPulse").animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        tween(650, easing = LinearEasing),
                        RepeatMode.Reverse,
                    ),
                    label = "pickAlpha",
                ).value
            } else {
                1f
            }
            val nodeTitle = when {
                picking -> "选择中…"
                resolvedNode != "未选择" -> resolvedNode
                else -> "未选择节点"
            }
            // 固定字号 + 固定行高：节点名长度/状态变化不影响下方布局
            Text(
                nodeTitle,
                color = when {
                    picking -> colors.textTertiary
                    else -> colors.text
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .then(if (picking) Modifier.graphicsLayer { alpha = pickPulse } else Modifier)
                    .clip(RoundedCornerShape(8.dp))
                    .pressableClick { onOpenTab(MainTab.Nodes) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )

            // ── 协议 · 延迟（点击测速）
            if (running && mainGroup != null) {
                val delay = delayOf(groups, delays)
                val delayText = when {
                    delay <= 0 -> "测速中"
                    delay > 65000 -> "超时"
                    else -> "${delay} ms"
                }
                val protocol = currentLeafTag(groups, delays, mainGroup)?.let { protocolByTag[it] }
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(protocol, delayText).joinToString(" · "),
                    color = colors.textTertiary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewModel.urlTest(ConfigBuilder.GROUP_TAG) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            } else {
                Spacer(Modifier.height(2.dp))
                // 与运行中的协议行同款内边距,两态行高一致,布局零漂移
                Text(
                    when (status) {
                        Status.Starting -> "正在建立隧道…"
                        Status.Stopping -> "正在断开…"
                        else -> "轻点图标以连接"
                    },
                    color = colors.textTertiary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── 状态标签: 居中三胶囊, 点击进入对应设置 ──
            val routingLabel = when (routingMode) {
                "global" -> "全局"
                "direct" -> "直连"
                else -> "规则"
            }
            val scopeOn = proxyScope.label != "全部应用"
            val switchModeLabel = when (storedSelected) {
                ConfigBuilder.AUTO_TAG -> "自动"
                ConfigBuilder.SMART_TAG -> "智能"
                else -> "手动"
            }
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatusChip(label = "路由模式", value = routingLabel) {
                    onOpenSubPage(SettingsSubPage.Proxy)
                }
                Spacer(Modifier.width(10.dp))
                StatusChip(label = "应用分流", value = if (scopeOn) "开" else "关") {
                    onOpenSubPage(SettingsSubPage.PerApp)
                }
                Spacer(Modifier.width(10.dp))
                StatusChip(label = "切换模式", value = switchModeLabel) {
                    onOpenTab(MainTab.Nodes)
                }
            }

            // 智能模式状态行: 悬于内核切换上方
            if (storedSelected == ConfigBuilder.SMART_TAG) {
                val smartState by viewModel.smartState.collectAsState()
                SmartStatusLine(
                    state = smartState,
                    running = running,
                    onClick = { onOpenTab(MainTab.Nodes) },
                )
            }

            Spacer(Modifier.height(8.dp))

            val coreOrder = listOf(
                com.interstellar.proxy.core.CoreKind.SINGBOX,
                com.interstellar.proxy.core.CoreKind.MIHOMO,
                com.interstellar.proxy.core.CoreKind.XRAY,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "内核",
                    color = colors.textTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                    modifier = Modifier.width(30.dp),
                )
                SegmentedControl(
                    items = coreOrder.map { it.displayName },
                    selected = coreOrder.indexOf(coreKind).coerceAtLeast(0),
                    onSelect = { i ->
                        val picked = coreOrder[i]
                        if (!busy && status != Status.Starting) {
                            viewModel.switchCore(picked)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    controlHeight = 40.dp,
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── 仪表网格：核心 / 流量曲线 / 网络探测 / 订阅
            val down = Libbox.formatBytes(speed.downlinkPerSecond)
            val up = Libbox.formatBytes(speed.uplinkPerSecond)
            val total = Libbox.formatBytes(speed.uplinkTotal + speed.downlinkTotal)

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                InstrumentCard(
                    caption = "核心",
                    onClick = { onOpenSubPage(SettingsSubPage.Logs) },
                    modifier = Modifier.weight(1f),
                    secondary = {
                        val coreLabel = when (coreKind) {
                            com.interstellar.proxy.core.CoreKind.MIHOMO -> MIHOMO_VERSION
                            com.interstellar.proxy.core.CoreKind.XRAY -> XRAY_VERSION_CARD
                            else -> CORE_VERSION
                        }
                        Text(
                            coreLabel,
                            color = colors.textTertiary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                ) {
                    if (running && connectedAt > 0L) {
                        TickingElapsed(connectedAt) { elapsed ->
                            Text(
                                elapsed,
                                color = colors.text,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                            )
                        }
                    } else {
                        Text(
                            "—",
                            color = colors.text,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
                val connectionCount = when (coreKind) {
                    com.interstellar.proxy.core.CoreKind.MIHOMO -> mihomoConnectionCount
                    else -> activeConnectionCount
                }
                InstrumentCard(
                    caption = "流量",
                    onClick = { onOpenSubPage(SettingsSubPage.Connections) },
                    modifier = Modifier.weight(1f),
                    secondary = {
                        Text(
                            "Σ $total · $connectionCount 连接",
                            color = colors.textTertiary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "↓ $down/s",
                            color = colors.success,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                        Text(
                            "↑ $up/s",
                            color = colors.danger,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                InstrumentCard(
                    caption = "出口网络",
                    onClick = { viewModel.probeNetwork() },
                    modifier = Modifier.weight(1f),
                    secondary = {
                        val sp = probe
                        if (sp is ProbeState.Done) {
                            Text(
                                listOfNotNull(
                                    sp.result.country,
                                    "${sp.result.latencyMs} ms",
                                    if (sp.result.viaProxy) "经代理" else "直连",
                                ).joinToString(" · "),
                                color = colors.textTertiary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                ) {
                    when (val p = probe) {
                        ProbeState.Running -> Text(
                            "探测中…",
                            color = colors.textTertiary,
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                        )

                        is ProbeState.Done -> Text(
                            p.result.ip,
                            color = colors.text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        is ProbeState.Failed -> Text(
                            "探测失败 · 点击重试",
                            color = colors.warning,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        ProbeState.Idle -> Text(
                            "点击检测出口 IP",
                            color = colors.textTertiary,
                            fontSize = 13.sp,
                        )
                    }
                }
                val activeSub = subscriptions.find { it.id == activeSubscriptionId }
                val quotaSubs = if (mixEnabled) {
                    subscriptions.filter { it.id in mixSubscriptionIds }
                } else {
                    listOfNotNull(activeSub)
                }
                val pool = remember(quotaSubs) { quotaSubs.sumOf { it.nodes.size } }
                val used = quotaSubs.sumOf { it.uploadBytes + it.downloadBytes }
                val totalBytes = quotaSubs.sumOf { it.totalBytes }
                val label = when {
                    mixEnabled && quotaSubs.isNotEmpty() -> "Mix · ${quotaSubs.size} 订阅"
                    activeSub != null -> activeSub.name
                    else -> "未添加"
                }
                InstrumentCard(
                    caption = "订阅",
                    onClick = { onOpenTab(MainTab.Subscriptions) },
                    modifier = Modifier.weight(1f),
                    secondary = {
                        if (totalBytes > 0) {
                            val fraction = (used.toFloat() / totalBytes).coerceIn(0f, 1f)
                            val barColor = when {
                                fraction >= 0.9f -> colors.danger
                                fraction >= 0.7f -> colors.warning
                                else -> colors.primary
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(colors.bgDeep),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction)
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(barColor),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            "$pool 节点" + if (totalBytes > 0) {
                                " · ${Libbox.formatBytes(used)} / ${Libbox.formatBytes(totalBytes)}"
                            } else {
                                ""
                            },
                            color = colors.textTertiary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                ) {
                    Text(
                        label,
                        color = colors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 主操作：连接/断开 + 切换节点
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GlassButton(
                    text = when {
                        running -> "断开连接"
                        status == Status.Starting -> "启动中…"
                        else -> "启动代理"
                    },
                    style = if (running) GlassButtonStyle.Danger else GlassButtonStyle.Primary,
                    enabled = !busy && status != Status.Starting,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (running) viewModel.stopProxy() else onStart()
                    },
                    modifier = Modifier.weight(1f),
                )
                GlassButton(
                    text = "切换节点",
                    style = GlassButtonStyle.Secondary,
                    onClick = { onOpenTab(MainTab.Nodes) },
                    modifier = Modifier.weight(1f),
                )
            }


            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 玻璃圆角图标按钮（顶栏）。 */
@Composable
private fun GlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val light = 0.2126f * colors.bg.red + 0.7152f * colors.bg.green + 0.0722f * colors.bg.blue > 0.5f
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .glassSurface(50.dp, light, colors.panelTop, colors.panelBottom, colors.border)
            .pressableClick(onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.textSecondary,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun InstrumentCaption(text: String) {
    val colors = LocalInterstellarColors.current
    Text(
        text,
        color = colors.textTertiary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
    )
}

/** 遥测卡：玻璃卡 + 左上小标签，所有卡统一尺寸。 */
@Composable
private fun InstrumentCard(
    caption: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    secondary: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    GlassCard(
        modifier = modifier
            .height(InstrumentCardHeight)
            .fillMaxWidth(),
        onClick = onClick,
        contentPadding = 10.dp,
    ) {
        Text(
            caption,
            color = colors.textTertiary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(4.dp))
        // 主要内容在剩余空间垂直居中, 次要内容贴底
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        secondary()
    }
}

/** All four dashboard instruments share one exact height so the grid stays uniform. */
/** Smart-mode status line, shown above the core segment while smart is on. */
@Composable
private fun SmartStatusLine(
    state: com.interstellar.proxy.ui.SmartSwitchEngine.SmartState,
    running: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val delayText = state.currentDelayMs.takeIf { it > 0 }?.let { "${it}ms" }
    val body = when {
        !running -> "连接后自动择优"
        state.alert != null -> state.alert
        else -> buildString {
            append(state.phase)
            state.currentTag?.let { append(" · $it") }
            delayText?.let { append(" · $it") }
        }
    }
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pressableClick(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            "智能",
            color = colors.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            body,
            color = if (state.alert != null) colors.warning else colors.textTertiary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}

@Composable
private fun StatusChip(label: String, value: String, onClick: () -> Unit) {
    val colors = LocalInterstellarColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.bgDeep)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = colors.textTertiary,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp,
        )
        Text(":", color = colors.textTertiary, fontSize = 11.sp)
        Text(
            value,
            color = colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private val InstrumentCardHeight = 104.dp

/** Hero：笑脸或轨道样式，按压缩放。 */
@Composable
private fun HeroButton(
    status: Status,
    enabled: Boolean,
    heroSize: Dp,
    style: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = Motion.snappy(),
        label = "heroScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(heroSize)
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        if (style == "orbit") {
            OrbitHero(status = status, heroSize = heroSize)
        } else {
            FaceMark(status = status, faceSize = heroSize)
        }
    }
}

/** 每秒走字的运行时长：ticker 状态收在叶子组件里,不触发整页重组。 */
@Composable
private fun TickingElapsed(connectedAt: Long, content: @Composable (String) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedAt) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    content(formatElapsed(now - connectedAt))
}

private fun formatElapsed(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun nodeRowValue(
    groups: List<CoreGroup>,
    delays: Map<String, Int>,
    mainGroup: CoreGroup?,
    storedSelected: String,
): String {
    // live core selection first; when 未连接 fall back to the persisted tag
    val selected = mainGroup?.selected?.takeIf { it.isNotBlank() }
        ?: storedSelected.takeIf { it.isNotBlank() }
        ?: return "未选择"
    val leaf = resolveNow(groups, delays, selected)
    return if (leaf == ConfigBuilder.AUTO_TAG || leaf == ConfigBuilder.GROUP_TAG) "自动" else leaf
}

/** Leaf node tag currently in use (null when it stays on a group / auto itself). */
private fun currentLeafTag(
    groups: List<CoreGroup>,
    delays: Map<String, Int>,
    mainGroup: CoreGroup?,
): String? {
    val selected = mainGroup?.selected?.takeIf { it.isNotBlank() } ?: return null
    val leaf = resolveNow(groups, delays, selected)
    return leaf.takeIf { it != ConfigBuilder.AUTO_TAG && it != ConfigBuilder.GROUP_TAG }
}

/**
 * Walk selector / urltest until the leaf in use. Urltest's Now() is empty
 * until the first full test finishes — fall back to the current fastest
 * (or first) member so the home row does not sit on "自动" for seconds.
 */
private fun resolveNow(
    groups: List<CoreGroup>,
    delays: Map<String, Int>,
    tag: String,
    depth: Int = 0,
): String {
    if (depth > 5) return tag
    val group = groups.find { it.tag == tag } ?: return tag
    val next = group.selected
    if (!next.isNullOrBlank() && next != tag) {
        return resolveNow(groups, delays, next, depth + 1)
    }
    val items = groupItems(group)
    if (items.isEmpty()) return tag
    val fastest = items.minByOrNull { delayOfItem(it, delays).takeIf { d -> d > 0 } ?: Int.MAX_VALUE }
    val candidate = when {
        fastest != null && delayOfItem(fastest, delays) > 0 -> fastest.tag
        else -> items.first().tag
    }
    return if (candidate == tag) tag else resolveNow(groups, delays, candidate, depth + 1)
}

private fun groupItems(group: com.interstellar.proxy.core.CoreGroup) = group.items

private fun delayOfItem(item: com.interstellar.proxy.core.CoreGroupItem, delays: Map<String, Int>): Int =
    delays[item.tag]?.takeIf { it > 0 } ?: item.urlTestDelay

private fun delayOf(groups: List<com.interstellar.proxy.core.CoreGroup>, delays: Map<String, Int>): Int {
    val main = groups.find { it.tag == ConfigBuilder.GROUP_TAG }
        ?: groups.firstOrNull { it.type.equals("selector", ignoreCase = true) }
        ?: return 0
    val selected = main.selected ?: return 0
    val leaf = resolveNow(groups, delays, selected)
    delays[leaf]?.let { if (it > 0) return it }
    delays[selected]?.let { if (it > 0) return it }
    return 0
}
