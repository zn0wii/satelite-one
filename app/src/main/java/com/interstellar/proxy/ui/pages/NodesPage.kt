package com.interstellar.proxy.ui.pages

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.data.SubscriptionRepository
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.data.config.ConfigBuilder
import com.interstellar.proxy.ui.AppViewModel
import com.interstellar.proxy.ui.components.IosSwitch
import com.interstellar.proxy.ui.components.PageHeader
import com.interstellar.proxy.ui.components.SegmentedControl
import com.interstellar.proxy.ui.components.glassSurface
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.LocalInterstellarColors

private data class NodeEntry(
    val tag: String,
    val type: String,
    val delay: Int = 0,
    val testedAt: Long = 0,
    val title: String? = null,
    /** Source subscription name in mix mode; null otherwise. */
    val source: String? = null,
    /** Full node model when the tag maps into the stored pool. */
    val node: com.interstellar.proxy.data.model.ProxyNode? = null,
) {
    val label: String get() = title ?: tag
}

private fun isGroupItem(item: NodeEntry): Boolean {
    // mihomo reports "Selector"/"URLTest" capitalized, libbox lowercase
    val t = item.type.lowercase()
    return item.tag == ConfigBuilder.AUTO_TAG || t == "urltest" || t == "selector"
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NodesPage(viewModel: AppViewModel) {
    val colors = LocalInterstellarColors.current
    val liveGroups by viewModel.groups.collectAsState()
    val staticGroups by viewModel.staticGroups.collectAsState()
    // 分组结构永远来自磁盘上的 active.json(内核无关,切订阅立即生效、
    // 内核死掉/重启中也不会失真);内核轮询只覆盖运行时状态:
    // delays 流 + 下面这张 tag→当前选中 的表
    val groups = staticGroups
    val liveSelectedByTag = remember(liveGroups) {
        liveGroups.mapNotNull { g -> g.selected?.takeIf { it.isNotBlank() }?.let { g.tag to it } }.toMap()
    }
    val delays by viewModel.delays.collectAsState()
    val testing by viewModel.testing.collectAsState()
    val message by viewModel.message.collectAsState()
    val splitRules by viewModel.splitRuleStatus.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val activeId by viewModel.activeSubscriptionId.collectAsState()
    val mixEnabled by viewModel.mixEnabled.collectAsState()
    val mixIds by viewModel.mixSubscriptionIds.collectAsState()
    val storedSelected by viewModel.selectedOutboundTag.collectAsState()
    val gridView by viewModel.nodesGridView.collectAsState()
    val smartState by viewModel.smartState.collectAsState()
    var sortMode by rememberSaveable { mutableStateOf(0) } // 0 延迟 1 名称
    var detailItem by remember { mutableStateOf<NodeEntry?>(null) }
    // group tabs: live groups from the core (mihomo raw configs carry their
    // own proxy-groups; rewritten configs carry ours). null = main tab.
    var activeTab by rememberSaveable { mutableStateOf<String?>(null) }

    // 虚拟 自动/智能 分组只在程序自己生成的配置结构上有意义:机场原始
    // 配置自带自动选择分组,再插一个只会重复,且其选择目标 (auto/smart)
    // 在原始配置里不存在,点了必然无效。判定:存在我们生成的 auto 组。
    val autoGroup = groups.find { it.tag == ConfigBuilder.AUTO_TAG }
    val virtualTabs = autoGroup != null

    val mainGroup = groups.find { it.tag == ConfigBuilder.GROUP_TAG }
        // raw configs may not name any group "proxy" — fall back to the first
        // selector so the page and dashboard still have a main tab
        ?: groups.firstOrNull { it.type.equals("selector", ignoreCase = true) }
        ?: groups.firstOrNull()
    val currentTab: com.interstellar.proxy.core.CoreGroup? =
        activeTab?.let { t -> groups.find { it.tag == t } } ?: mainGroup
    val isMainTab = currentTab?.tag == mainGroup?.tag
    val isSmartTab = virtualTabs && activeTab == ConfigBuilder.SMART_TAG
    val tabSelectable = currentTab?.type?.equals("selector", ignoreCase = true) == true
    // the pool the generated config runs on: active sub, or the mix union
    val storedNodes = remember(subscriptions, activeId, mixEnabled, mixIds) {
        SubscriptionRepository.poolOf(subscriptions, activeId, mixEnabled, mixIds)
    }
    // mix source labels: tags follow pool order, so segment by subscription
    val sourceByTag = remember(subscriptions, storedNodes, mixEnabled, mixIds) {
        if (!mixEnabled) {
            emptyMap()
        } else {
            val names = buildList {
                subscriptions.filter { it.id in mixIds }.forEach { sub ->
                    repeat(sub.nodes.size) { add(sub.name) }
                }
            }
            ConfigBuilder.tagsFor(storedNodes).zip(names).toMap()
        }
    }

    // ── 底部测速/Ping 进度条与完成摘要 ──
    val testProg by viewModel.testProgress.collectAsState()
    val pingRunning by viewModel.pinging.collectAsState()
    val pingProg by viewModel.pingProgress.collectAsState()
    var testSummary by remember { mutableStateOf<NodesTestSummary?>(null) }
    var prevRunning by remember { mutableStateOf(false) }
    var lastMode by remember { mutableStateOf("测速") }
    val testRunning = testing || pingRunning
    LaunchedEffect(testRunning) {
        if (testRunning) {
            lastMode = if (pingRunning) "Ping" else "测速"
            testSummary = null // a new run clears the old summary
        } else if (prevRunning) {
            // just finished — snapshot stats over the current pool tags
            val tags = ConfigBuilder.tagsFor(storedNodes)
            val pingReport = if (lastMode == "Ping") viewModel.lastPingReport else null
            testSummary = computeNodesTestSummary(lastMode, tags, delays, pingReport)
        }
        prevRunning = testRunning
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // 当前节点池来源:激活订阅;Mix 则多个订阅名以 · 连接
        val activeSub = subscriptions.find { it.id == activeId }
        val subNote = when {
            mixEnabled && mixIds.isNotEmpty() ->
                subscriptions.filter { it.id in mixIds }.joinToString(" · ") { it.name }
            activeSub != null -> activeSub.name
            else -> null
        }
        PageHeader(kicker = "NODES", title = "节点", titleNote = subNote)

        val liveItems = remember(currentTab) {
            currentTab?.items ?: emptyList()
        }
        // tag → full node model, so cards and the detail sheet can show protocol info
        val nodeByTag = remember(storedNodes) {
            ConfigBuilder.tagsFor(storedNodes).zip(storedNodes).toMap()
        }
        val allItems = remember(liveItems, storedNodes, delays, sourceByTag, nodeByTag) {
            if (liveItems.isNotEmpty()) {
                liveItems.map { item ->
                    NodeEntry(
                        tag = item.tag,
                        type = item.type,
                        delay = delays[item.tag] ?: item.urlTestDelay,
                        testedAt = item.urlTestTime,
                        source = sourceByTag[item.tag],
                        node = nodeByTag[item.tag],
                    )
                }
            } else {
                val tags = ConfigBuilder.tagsFor(storedNodes)
                storedNodes.zip(tags).map { (node, tag) ->
                    NodeEntry(
                        tag,
                        node.type.wire,
                        delays[tag] ?: 0,
                        source = sourceByTag[tag],
                        node = node,
                    )
                }
            }
        }
        val nodeItems = remember(allItems) { allItems.filterNot(::isGroupItem) }
        // group references (region / airport sub-groups) ride at the list head
        // as tappable group cards — tapping points THIS group at them. The
        // auto group reference is skipped: the top 自动 tab IS its switch.
        val groupItems = remember(allItems) {
            allItems.filter(::isGroupItem).filterNot { it.tag == ConfigBuilder.AUTO_TAG }
        }
        // smart mode is only ever visible in the STORED selection (the live
        // group always names a concrete node) — it wins the highlight race;
        // on the smart tab highlight the node the engine currently rides.
        // Selection precedence: LIVE core state first (fresh truth), then the
        // stored pick (instant feedback while the stopped core just baked it
        // into the config), then the config's baked default. Static-first
        // would shadow every runtime switch with a stale baked default.
        val runtimeSelected: String? =
            (liveSelectedByTag[currentTab?.tag] ?: currentTab?.selected)?.takeIf { it.isNotBlank() }
        val mainRuntimeSelected: String? = when {
            mainGroup == null -> null
            else -> liveSelectedByTag[mainGroup.tag]
                ?: storedSelected?.takeIf { it.isNotBlank() && it != ConfigBuilder.SMART_TAG }
                ?: mainGroup.selected
        }?.takeIf { it.isNotBlank() }
        val selectedTag = when {
            isSmartTab -> smartState.currentTag ?: ""
            !isMainTab -> runtimeSelected ?: ""
            storedSelected == ConfigBuilder.SMART_TAG -> ConfigBuilder.SMART_TAG
            else -> {
                val sel = mainRuntimeSelected
                // 选中项本身是个分组(auto / 机场 ♻️)时,高亮穿透到该分组
                // 当前指向的节点 —— 点自动后主列表能看到实际出口
                val through = if (sel != null && groups.any { it.tag == sel }) {
                    liveSelectedByTag[sel] ?: groups.find { it.tag == sel }?.selected
                } else {
                    sel
                }
                through
                    ?: storedSelected.takeIf { it.isNotBlank() }
                    ?: ConfigBuilder.AUTO_TAG
            }
        }

        if (nodeItems.isEmpty()) {
            EmptyHint(
                text = if (mixEnabled) {
                    "Mix 未勾选订阅,或所选订阅中没有可用节点"
                } else if (storedNodes.isEmpty()) {
                    "请先添加并激活一个订阅"
                } else {
                    "订阅中没有可用节点"
                },
            )
            return@Column
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("分流规则启用", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !splitRules.hasEnabledRules -> "未设置规则，可在设置里添加"
                        splitRules.active -> "指定域名走过滤后的节点组"
                        else -> "已关闭，全部走当前节点"
                    },
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                )
            }
            IosSwitch(
                checked = splitRules.masterEnabled,
                onChange = { viewModel.setSplitRulesEnabled(it) },
            )
        }

        // ── 分组 tab: 虚拟 自动/智能 置前(仅程序生成配置),后跟真实分组 ──
        val tabs = buildList {
            if (virtualTabs) {
                add(GroupTab(ConfigBuilder.AUTO_TAG, auto = true))
                add(GroupTab(ConfigBuilder.SMART_TAG, auto = false))
            }
            groups.filterNot { virtualTabs && it.tag == ConfigBuilder.AUTO_TAG }.forEach {
                add(GroupTab(it.tag, auto = it.type.equals("urltest", ignoreCase = true)))
            }
        }
        if (tabs.size > 1) {
            GroupTabRow(
                tabs = tabs,
                selectedTag = activeTab ?: mainGroup?.tag,
                onSelect = { tag ->
                    when {
                        tag == ConfigBuilder.SMART_TAG -> {
                            activeTab = ConfigBuilder.SMART_TAG
                            // tapping the smart tab engages the engine (a node
                            // pick inside exits it, mirroring the old card)
                            viewModel.selectSmartMode()
                        }

                        else -> {
                            activeTab = if (tag == mainGroup?.tag) null else tag
                            // tapping a urltest group tab (自动 / airport ♻️)
                            // that the main selector carries = switch onto it
                            val tapped = groups.find { it.tag == tag }
                            val main = mainGroup
                            if (tapped != null && main != null &&
                                tapped.type.equals("urltest", ignoreCase = true) &&
                                main.items.any { it.tag == tag } &&
                                main.selected != tag
                            ) {
                                viewModel.selectNode(main.tag, tag)
                            }
                        }
                    }
                },
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

        // 状态不设横幅:插入/移除文字行会把列表挤跑。当前自动/智能/组
        // 选择一律用高亮表达 —— 选中 tab、当前节点卡片、主 tab 组引用卡片。

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SegmentedControl(
                items = listOf("延迟", "名称"),
                selected = sortMode,
                onSelect = { sortMode = it },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (gridView) colors.primaryMuted else colors.panel)
                    .pressableClick { viewModel.setNodesGridView(!gridView) },
            ) {
                Icon(
                    imageVector = if (gridView) Icons.Filled.ViewAgenda else Icons.Filled.GridView,
                    contentDescription = if (gridView) "列表" else "网格",
                    tint = if (gridView) colors.primary else colors.text,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            val testProgress by viewModel.testProgress.collectAsState()
            val testActive = testing || testProgress != null
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.primaryMuted)
                    .pressableClick { viewModel.urlTest(currentTab?.tag ?: ConfigBuilder.GROUP_TAG) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                if (testActive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
                        val sweep by transition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                androidx.compose.animation.core.tween(
                                    900,
                                    easing = androidx.compose.animation.core.LinearEasing,
                                ),
                            ),
                            label = "spinA",
                        )
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(14.dp)) {
                            drawArc(
                                color = colors.primary,
                                startAngle = sweep,
                                sweepAngle = 270f,
                                useCenter = false,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    2.dp.toPx(),
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                ),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "测速中",
                            color = colors.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    Text("测速", color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.width(8.dp))
            // 直连 TCP Ping：不依赖内核，即时并发，结果流式回填
            val pinging by viewModel.pinging.collectAsState()
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (pinging) colors.primaryMuted else colors.bgDeep)
                    .pressableClick { viewModel.tcpPingPool() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (pinging) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "spinP")
                        val sweep by transition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                androidx.compose.animation.core.tween(
                                    900,
                                    easing = androidx.compose.animation.core.LinearEasing,
                                ),
                            ),
                            label = "spinPA",
                        )
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(14.dp)) {
                            drawArc(
                                color = colors.accent,
                                startAngle = sweep,
                                sweepAngle = 270f,
                                useCenter = false,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    2.dp.toPx(),
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                ),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Ping中",
                            color = colors.accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    Text("Ping", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        message?.let {
            Text(
                it,
                color = colors.warning,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        // ── 进度条/统计摘要: 悬于节点卡片上方 ──
        if (testRunning || testSummary != null) {
            TestSummaryBar(
                running = testRunning,
                mode = lastMode,
                progress = if (pingRunning) pingProg else testProg,
                summary = testSummary,
                onDismiss = { testSummary = null },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        val sorted = remember(nodeItems, sortMode, delays) {
            when (sortMode) {
                1 -> nodeItems.sortedBy { it.tag.lowercase() }
                else -> nodeItems.sortedBy { item ->
                    val d = delays[item.tag]?.takeIf { it > 0 } ?: item.delay
                    if (d > 0) d else Int.MAX_VALUE
                }
            }
        }
        // 组引用卡片置顶,后跟按序节点
        val displayed = groupItems + sorted
        val nodeCtx = androidx.compose.ui.platform.LocalContext.current
        val onNodeTap: (NodeEntry) -> Unit = { item ->
            when {
                item.tag == ConfigBuilder.SMART_TAG -> viewModel.selectSmartMode()
                // urltest groups pick their own node — selection taps are no-ops
                !tabSelectable && !isMainTab -> Unit
                else -> viewModel.selectNode(currentTab?.tag ?: ConfigBuilder.GROUP_TAG, item.tag)
            }
        }

        if (gridView) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // adaptive by width, capped at 3 columns per row
                val columns = ((maxWidth / 148.dp).toInt() + 1).coerceIn(1, 3)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                items(displayed, key = { it.tag }) { item ->
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.alpha(if (item.tag == ConfigBuilder.SMART_TAG && selectedTag != ConfigBuilder.SMART_TAG) 0.6f else 1f),
                    ) {
                        NodeGridCell(
                            item = item,
                            delay = delays[item.tag] ?: item.delay,
                            selected = item.tag == selectedTag,
                            onClick = { onNodeTap(item) },
                            onLongPress = { if (!isGroupItem(item)) detailItem = item },
                        )
                    }
                }
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(displayed, key = { it.tag }) { item ->
                    Box(
                        modifier = Modifier.alpha(if (item.tag == ConfigBuilder.SMART_TAG && selectedTag != ConfigBuilder.SMART_TAG) 0.6f else 1f),
                    ) {
                        NodeRow(
                            item = item,
                            delay = delays[item.tag] ?: item.delay,
                            selected = item.tag == selectedTag,
                            onClick = { onNodeTap(item) },
                            onLongPress = { if (!isGroupItem(item)) detailItem = item },
                        )
                    }
                }
            }
        }
    }

    detailItem?.let { item ->
        NodeDetailSheet(item = item, onDismiss = { detailItem = null })
    }
}

/** Stats snapshot shown after a url-test / ping run finishes. */
private data class NodesTestSummary(
    val mode: String,
    val total: Int,
    val okCount: Int,
    val minMs: Int,
    val maxMs: Int,
    val p50: Int,
    val p95: Int,
    val failed: Int = 0,
    /** Aggregated failure reasons ("超时 12 · 连接被拒 3"), or a generic note for kernel tests. */
    val failNote: String? = null,
    /** UDP-protocol nodes TCP ping can't cover (use url-test instead). */
    val skippedUdp: Int = 0,
)

/** ok = tested with a real delay (sentinels and untested excluded). */
private fun computeNodesTestSummary(
    mode: String,
    tags: List<String>,
    delays: Map<String, Int>,
    pingReport: com.interstellar.proxy.ui.AppViewModel.PingReport? = null,
): NodesTestSummary {
    val values = tags.mapNotNull { tag ->
        delays[tag]?.takeIf { it > 0 && it < 65_000 }
    }.sorted()
    fun pct(p: Double) = if (values.isEmpty()) 0 else values[((values.size - 1) * p).toInt()]
    val skippedUdp = pingReport?.skippedUdp ?: 0
    val failed = pingReport?.failed ?: (tags.size - values.size - 0).coerceAtLeast(0)
    val failNote = when {
        failed <= 0 -> null

        pingReport != null && pingReport.reasons.isNotEmpty() ->
            pingReport.reasons.entries.sortedByDescending { it.value }
                .joinToString(" · ") { "${it.key} ${it.value}" }

        else -> "超时或握手失败"
    }
    return NodesTestSummary(
        mode = mode,
        total = tags.size,
        okCount = values.size,
        minMs = values.firstOrNull() ?: 0,
        maxMs = values.lastOrNull() ?: 0,
        p50 = pct(0.50),
        p95 = pct(0.95),
        failed = failed,
        failNote = failNote,
        skippedUdp = skippedUdp,
    )
}

@Composable
private fun TestSummaryBar(
    running: Boolean,
    mode: String,
    progress: Pair<Int, Int>?,
    summary: NodesTestSummary?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalInterstellarColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.panelSolid)
            .pressableClick { onDismiss() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${mode}中",
                    color = colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    progress?.let { "${it.first}/${it.second}" } ?: "…",
                    color = colors.textTertiary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "完成后展示统计",
                    color = colors.textTertiary,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            val fraction = progress?.takeIf { it.second > 0 }?.let { it.first.toFloat() / it.second } ?: 0f
            androidx.compose.material3.LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = colors.primary,
                trackColor = colors.primaryMuted,
            )
        } else if (summary != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${summary.mode}完成",
                    color = colors.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${summary.total} 节点 · 成功 ${summary.okCount} · 失败 ${summary.failed}",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                Text("点按关闭", color = colors.textTertiary, fontSize = 10.sp)
            }
            if (summary.okCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "最低 ${summary.minMs}ms · P50 ${summary.p50}ms · P95 ${summary.p95}ms · 最高 ${summary.maxMs}ms",
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (summary.skippedUdp > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "已跳过 ${summary.skippedUdp} 个 UDP 协议节点 (hysteria2/tuic/wireguard 不支持 TCP Ping, 请用测速)",
                    color = colors.warning,
                    fontSize = 11.sp,
                )
            }
            if (summary.failNote != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "失败原因: ${summary.failNote}",
                    color = if (summary.okCount == 0) colors.danger else colors.textTertiary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeRow(
    item: NodeEntry,
    delay: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val light = 0.2126f * colors.bg.red + 0.7152f * colors.bg.green + 0.0722f * colors.bg.blue > 0.5f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (selected) {
                    Modifier
                        .background(colors.primaryMuted)
                        .border(1.dp, colors.primaryBorder, RoundedCornerShape(14.dp))
                } else {
                    Modifier.glassSurface(14.dp, light, colors.panelTop, colors.panelBottom, colors.border)
                },
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) colors.primary else colors.border),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.label,
                    color = if (selected) colors.text else colors.textSecondary,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        isGroupItem(item) && item.type.equals("urltest", true) -> "自动测速分组 ›"
                        isGroupItem(item) -> "分组 ›"
                        item.node != null -> "${item.node.protocolSummary()} · ${item.node.server}:${item.node.port}"
                        else -> ""
                    },
                    color = colors.textTertiary,
                    fontSize = 11.sp,
                    fontFamily = if (isGroupItem(item)) null else FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.source?.let { source ->
                Spacer(Modifier.width(8.dp))
                Text(
                    source,
                    color = colors.textTertiary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 128.dp),
                )
            }
            if (isGroupItem(item)) {
                Text("›", color = colors.textTertiary, fontSize = 18.sp)
            } else {
                DelayBadge(delay, tested = item.testedAt > 0)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeGridCell(
    item: NodeEntry,
    delay: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val light = 0.2126f * colors.bg.red + 0.7152f * colors.bg.green + 0.0722f * colors.bg.blue > 0.5f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (selected) {
                    Modifier
                        .background(colors.primaryMuted)
                        .border(1.dp, colors.primaryBorder, RoundedCornerShape(14.dp))
                } else {
                    Modifier.glassSurface(14.dp, light, colors.panelTop, colors.panelBottom, colors.border)
                },
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(end = 4.dp, bottom = 40.dp),
        ) {
            Text(
                item.label,
                color = if (selected) colors.text else colors.textSecondary,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.source?.let { source ->
                Spacer(Modifier.height(2.dp))
                Text(
                    source,
                    color = colors.textTertiary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 协议信息独占一行（全宽），延迟徽章另起一行靠右；分组引用不带延迟
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(end = 4.dp),
        ) {
            when {
                isGroupItem(item) -> Text(
                    if (item.type.equals("urltest", true)) "自动测速分组" else "分组",
                    color = colors.textTertiary,
                    fontSize = 10.sp,
                    maxLines = 1,
                )

                item.node != null -> {
                    Text(
                        item.node.protocolSummary(),
                        color = colors.textTertiary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                }
            }
            if (!isGroupItem(item)) {
                DelayBadge(
                    delay = delay,
                    modifier = Modifier.align(Alignment.End),
                    tested = item.testedAt > 0,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeDetailSheet(item: NodeEntry, onDismiss: () -> Unit) {
    val colors = LocalInterstellarColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.panelSolid,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 26.dp),
        ) {
            Text("节点信息", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            DetailRow("名称", item.tag)
            item.source?.let { DetailRow("来源", it) }
            DetailRow("延迟", when {
                item.delay > 0 -> "${item.delay} ms"
                item.testedAt > 0 -> "超时"
                else -> "未测速"
            })
            DetailRow(
                "最近测速",
                if (item.testedAt > 0) {
                    java.text.DateFormat.getDateTimeInstance()
                        .format(java.util.Date(item.testedAt * 1000))
                } else {
                    "—"
                },
            )

            item.node?.let { n ->
                SheetSection("协议")
                DetailRow("类型", n.type.wire)
                DetailRow("传输", when (n.network) {
                    "tcp" -> "TCP"
                    "ws" -> "WebSocket"
                    "grpc" -> "gRPC"
                    "http" -> "HTTP/2"
                    "h2" -> "HTTP/2"
                    "quic" -> "QUIC"
                    else -> n.network
                })
                DetailRow(
                    "安全",
                    when {
                        n.reality != null -> "REALITY"
                        n.tls -> "TLS"
                        else -> "无"
                    },
                )
                DetailRow("摘要", n.protocolSummary())
                n.flow?.let { DetailRow("Flow", it) }
                n.sni?.let { DetailRow("SNI", it) }
                n.alpn?.takeIf { it.isNotEmpty() }?.let { DetailRow("ALPN", it.joinToString(", ")) }
                n.fingerprint?.let { DetailRow("uTLS 指纹", it) }
                if (n.insecure == true) DetailRow("允许不安全", "是")

                SheetSection("服务器")
                DetailRow("地址", n.server)
                DetailRow("端口", "${n.port}")
                n.udp?.let { DetailRow("UDP", if (it) "支持" else "不支持") }
                n.wsPath?.let { DetailRow("WS 路径", it) }
                n.grpcServiceName?.let { DetailRow("gRPC 服务名", it) }
                n.httpHost?.takeIf { it.isNotEmpty() }?.let { DetailRow("HTTP Host", it.joinToString(", ")) }
                n.httpPath?.let { DetailRow("HTTP 路径", it) }
                n.headers?.takeIf { it.isNotEmpty() }?.let {
                    DetailRow("额外 Header", it.entries.joinToString(" · ") { (k, v) -> "$k=$v" })
                }

                // 协议特定参数
                val params = buildList {
                    n.method?.let { add("加密" to it) }
                    n.alterId?.let { add("alterId" to "$it") }
                    n.security?.let { add("VMess 加密" to it) }
                    if (n.type == com.interstellar.proxy.data.model.NodeType.HYSTERIA2 && !n.hy2ObfsPassword.isNullOrBlank()) {
                        add("混淆" to "已启用")
                    }
                    n.upMbps?.let { add("上行" to "$it Mbps") }
                    n.downMbps?.let { add("下行" to "$it Mbps") }
                    n.congestionControl?.let { add("拥塞控制" to it) }
                    n.udpRelayMode?.let { add("UDP 中继" to it) }
                    n.shadowTls?.let { add("Shadow-TLS" to "v${it.version}") }
                    n.plugin?.let { p ->
                        add(
                            "插件" to buildString {
                                append(p)
                                n.pluginOpts?.takeIf { it.isNotEmpty() }?.let { opts ->
                                    append(" (")
                                    append(opts.entries.joinToString("; ") { (k, v) -> "$k=$v" })
                                    append(")")
                                }
                            },
                        )
                    }
                    n.wireguard?.let { wg ->
                        add("WireGuard" to "${wg.localAddress.size} 个本地地址 · MTU ${wg.mtu ?: 1420}")
                    }
                }
                if (params.isNotEmpty()) {
                    SheetSection("参数")
                    params.forEach { (k, v) -> DetailRow(k, v) }
                }

                SheetSection("凭据")
                n.uuid?.let { DetailRow("UUID", it) }
                n.password?.let { DetailRow("密码", it) }
                n.username?.let { DetailRow("用户名", it) }
                n.sshUser?.let { DetailRow("SSH 用户", it) }
                n.sshKey?.let { DetailRow("SSH 私钥", it) }
                n.hy2ObfsPassword?.let { DetailRow("混淆密码", it) }
                n.reality?.let { reality ->
                    DetailRow("REALITY 公钥", reality.publicKey)
                    reality.shortId?.let { DetailRow("REALITY shortId", it) }
                }
                n.wireguard?.let { wg ->
                    DetailRow("WG 私钥", wg.privateKey)
                    wg.peerPublicKey?.let { DetailRow("WG 对端公钥", it) }
                    wg.preSharedKey?.let { DetailRow("PSK", it) }
                }
                if (n.uuid == null && n.password == null && n.sshUser == null && n.username == null &&
                    n.sshKey == null && n.hy2ObfsPassword == null && n.reality == null && n.wireguard == null
                ) {
                    DetailRow("—", "此协议无凭据字段")
                }
            }
        }
    }
}

@Composable
private fun SheetSection(title: String) {
    val colors = LocalInterstellarColors.current
    Text(
        title,
        color = colors.textTertiary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = LocalInterstellarColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.textTertiary, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = colors.text,
            fontSize = 13.sp,
            // long credentials (keys) wrap instead of being cut off
            maxLines = if (value.length > 60) 8 else 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
fun DelayBadge(delay: Int, modifier: Modifier = Modifier, tested: Boolean = false) {
    val colors = LocalInterstellarColors.current
    val (text, color) = when {
        // stamped but no delay → the test ran and the node failed/timed out
        delay <= 0 -> if (tested) "超时" to colors.danger else "未测" to colors.textTertiary
        delay >= 65000 -> "超时" to colors.danger
        delay < 200 -> "${delay}ms" to colors.success
        delay < 300 -> "${delay}ms" to colors.warning
        else -> "${delay}ms" to colors.danger
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, fontSize = 12.sp)
    }
}

@Composable
fun EmptyHint(text: String) {
    val colors = LocalInterstellarColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 60.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(text, color = colors.textTertiary, fontSize = 13.sp)
    }
}

/** One tab chip: a real core group, or a virtual 自动/智能 entry. */
private data class GroupTab(val tag: String, val auto: Boolean)

/** Wrapping group tabs (airport configs carry a dozen+ groups). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GroupTabRow(
    tabs: List<GroupTab>,
    selectedTag: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalInterstellarColors.current
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        tabs.forEach { tab ->
            val selected = tab.tag == selectedTag
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) colors.primaryMuted else colors.bgDeep)
                    .border(
                        1.dp,
                        if (selected) colors.primaryBorder else colors.border,
                        RoundedCornerShape(50),
                    )
                    .pressableClick { onSelect(tab.tag) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    tab.tag,
                    color = if (selected) colors.primary else colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
                if (tab.auto) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "⚡",
                        color = if (selected) colors.primary else colors.textTertiary,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
