package com.interstellar.proxy.ui.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.R
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.data.SubscriptionRepository
import com.interstellar.proxy.data.UpdateWorker
import com.interstellar.proxy.ui.AppViewModel
import com.interstellar.proxy.ui.components.IosCard
import com.interstellar.proxy.ui.components.IosSectionFooter
import com.interstellar.proxy.ui.components.IosSectionLabel
import com.interstellar.proxy.ui.components.IosToggleRow
import com.interstellar.proxy.ui.components.PageHeader
import com.interstellar.proxy.ui.components.SegmentedControl
import com.interstellar.proxy.ui.components.iosPressable
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.LocalInterstellarColors
import com.interstellar.proxy.ui.theme.Motion
import io.nekohasekai.libbox.Libbox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsPage(viewModel: AppViewModel) {
    val colors = LocalInterstellarColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val subscriptions by viewModel.subscriptions.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val activeId by viewModel.activeSubscriptionId.collectAsState()
    val mixEnabled by viewModel.mixEnabled.collectAsState()
    val mixIds by viewModel.mixSubscriptionIds.collectAsState()
    val useRawConfig by viewModel.useRawConfig.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<SubscriptionRepository.Subscription?>(null) }
    var deleteTarget by remember { mutableStateOf<SubscriptionRepository.Subscription?>(null) }
    var autoUpdate by remember { mutableStateOf(Settings.autoUpdateEnabled) }
    var interval by remember { mutableStateOf(Settings.autoUpdateIntervalHours) }

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refreshAll() },
            modifier = Modifier.fillMaxSize(),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            PageHeader(
                kicker = "SUBSCRIPTIONS",
                title = stringResource(R.string.subs_title),
                trailing = {
                    Text(
                        stringResource(R.string.subs_add),
                        color = colors.accent,
                        fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .iosPressable { showAdd = true }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                },
            )

            IosSectionLabel(stringResource(R.string.subs_config_mode_section))
            IosCard(modifier = Modifier.fillMaxWidth()) {
                IosToggleRow(
                    title = stringResource(R.string.subs_use_raw_config_title),
                    subtitle = when {
                        useRawConfig -> stringResource(R.string.subs_use_raw_config_on)
                        else -> stringResource(R.string.subs_use_raw_config_off)
                    },
                    checked = useRawConfig,
                    onChange = { viewModel.setUseRawConfig(it) },
                )
            }
            IosSectionFooter(
                stringResource(R.string.subs_use_raw_config_footer),
            )

            Spacer(Modifier.height(8.dp))
            IosSectionLabel(stringResource(R.string.subs_mix_section))
            IosCard(modifier = Modifier.fillMaxWidth()) {
                IosToggleRow(
                    title = stringResource(R.string.subs_mix_title),
                    subtitle = when {
                        useRawConfig -> stringResource(R.string.subs_mix_unavailable)
                        mixEnabled -> stringResource(R.string.subs_mix_checked_count, mixIds.size, subscriptions.size)
                        else -> null
                    },
                    checked = mixEnabled,
                    enabled = !useRawConfig,
                    onChange = { viewModel.setMixEnabled(it) },
                )
            }
            IosSectionFooter(stringResource(R.string.subs_mix_footer))

            Spacer(Modifier.height(8.dp))
            IosSectionLabel(stringResource(R.string.subs_auto_update_section))
            IosCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    IosToggleRow(
                        title = stringResource(R.string.subs_auto_update_title),
                        checked = autoUpdate,
                        onChange = {
                            autoUpdate = it
                            Settings.autoUpdateEnabled = it
                            UpdateWorker.reschedule(context)
                        },
                    )
                    if (autoUpdate) {
                        Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                            SegmentedControl(
                                items = listOf(
                                    stringResource(R.string.subs_interval_hourly),
                                    stringResource(R.string.subs_interval_6h),
                                    stringResource(R.string.subs_interval_12h),
                                    stringResource(R.string.subs_interval_daily),
                                ),
                                selected = listOf(1, 6, 12, 24).indexOf(interval).coerceAtLeast(1),
                                onSelect = { index ->
                                    interval = listOf(1, 6, 12, 24)[index]
                                    Settings.autoUpdateIntervalHours = interval
                                    UpdateWorker.reschedule(context)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            IosSectionFooter(stringResource(R.string.subs_auto_update_footer))

            Spacer(Modifier.height(8.dp))
            IosSectionLabel(stringResource(R.string.subs_list_section))

            if (subscriptions.isEmpty()) {
                EmptyHint(text = stringResource(R.string.subs_empty_hint))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    subscriptions.forEach { sub ->
                        val isActive = !mixEnabled && sub.id == activeId
                        IosCard(
                            modifier = Modifier.fillMaxWidth(),
                            // the in-use card gets a clearly visible accent ring
                            // (primaryBorder is a 30%-alpha hairline — too faint here)
                            border = if (isActive) colors.primary.copy(alpha = 0.6f) else null,
                        ) {
                            SubscriptionCard(
                                sub = sub,
                                active = isActive,
                                checked = if (mixEnabled) sub.id in mixIds else null,
                                onClick = {
                                    if (mixEnabled) {
                                        viewModel.toggleMixSubscription(sub.id)
                                    } else {
                                        viewModel.activateSubscription(sub.id)
                                    }
                                },
                                onRefresh = { viewModel.refreshSubscription(sub.id, fromPull = false) },
                                onEdit = { editTarget = sub },
                                onDelete = { deleteTarget = sub },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        }
        SubscriptionsToast(viewModel, Modifier.align(Alignment.BottomCenter))
    }

    editTarget?.let { target ->
        EditSubscriptionDialog(
            subscription = target,
            onSave = { name, url ->
                viewModel.updateSubscription(target.id, name, url)
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }

    deleteTarget?.let { target ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { deleteTarget = null }) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.panelSolid)
                    .padding(18.dp),
            ) {
                Text(
                    stringResource(R.string.subs_delete_title),
                    color = colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.subs_delete_confirm, target.name),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionChip(text = stringResource(R.string.subs_cancel)) { deleteTarget = null }
                    Spacer(Modifier.width(10.dp))
                    ActionChip(text = stringResource(R.string.subs_delete), danger = true) {
                        viewModel.removeSubscription(target.id)
                        deleteTarget = null
                    }
                }
            }
        }
    }

    if (showAdd) {
        val adding by viewModel.addingSub.collectAsState()
        val addError by viewModel.addSubError.collectAsState()
        // close the dialog when the import lands; keep it open on failure so
        // the typed URL survives for a retry
        val countAtOpen = remember { viewModel.subscriptions.value.size }
        androidx.compose.runtime.LaunchedEffect(adding) {
            if (!adding && viewModel.subscriptions.value.size > countAtOpen) {
                showAdd = false
            }
        }
        AddSubscriptionDialog(
            loading = adding,
            onCancel = { viewModel.cancelAddSubscription() },
            error = addError,
            onDismiss = { showAdd = false },
            onAddUrl = { name, url ->
                viewModel.addSubscriptionFromUrl(name, url)
            },
            onAddText = { name, text ->
                viewModel.addSubscriptionFromText(name, text)
            },
        )
    }
}

@Composable
private fun SubscriptionCard(
    sub: SubscriptionRepository.Subscription,
    active: Boolean,
    /** Mix mode: checked state of this subscription; null = activate mode. */
    checked: Boolean?,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val used = sub.uploadBytes + sub.downloadBytes
    val ratio = if (sub.totalBytes > 0) {
        (used.toFloat() / sub.totalBytes).coerceIn(0f, 1f)
    } else {
        0f
    }
    val expireText = if (sub.expireSeconds > 0) {
        val days = ((sub.expireSeconds * 1000 - System.currentTimeMillis()) / 86_400_000L).toInt()
        when {
            days < 0 -> stringResource(R.string.subs_expired)
            days == 0 -> stringResource(R.string.subs_expire_today)
            else -> stringResource(R.string.subs_expire_in_days, days)
        }
    } else {
        null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .iosPressable { onClick() }
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                sub.name,
                color = colors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                checked != null -> Icon(
                    imageVector = if (checked) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Outlined.RadioButtonUnchecked
                    },
                    contentDescription = if (checked) {
                        stringResource(R.string.subs_joined)
                    } else {
                        stringResource(R.string.subs_not_joined)
                    },
                    tint = if (checked) colors.primary else colors.border,
                    modifier = Modifier.size(22.dp),
                )

                active -> Text(
                    stringResource(R.string.subs_active),
                    color = colors.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (sub.totalBytes > 0) {
                    "${Libbox.formatBytes(used)} / ${Libbox.formatBytes(sub.totalBytes)}"
                } else {
                    expireText ?: " "
                },
                color = colors.textTertiary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.subs_node_count, sub.nodes.size),
                color = colors.textSecondary,
                fontSize = 13.sp,
            )
            val format = com.interstellar.proxy.data.subscription.RawConfigFormat.from(sub.configFormat)
            if (format != null) {
                Spacer(Modifier.width(8.dp))
                // raw only takes effect when the format matches the running
                // core and the retained body is still on disk — show the truth
                val rawOn = Settings.useRawConfigEnabled
                val coreKind = Settings.coreKind
                val matchesCore = when (format) {
                    com.interstellar.proxy.data.subscription.RawConfigFormat.CLASH ->
                        coreKind == com.interstellar.proxy.core.CoreKind.MIHOMO
                    com.interstellar.proxy.data.subscription.RawConfigFormat.SINGBOX ->
                        coreKind == com.interstellar.proxy.core.CoreKind.SINGBOX
                    com.interstellar.proxy.data.subscription.RawConfigFormat.XRAY ->
                        coreKind == com.interstellar.proxy.core.CoreKind.XRAY
                }
                val effective = rawOn && matchesCore &&
                    SubscriptionRepository.rawFileOf(sub.id).isFile
                FormatBadge(
                    label = format.label,
                    active = rawOn,
                    activeHint = if (effective) {
                        stringResource(R.string.subs_raw_badge_on)
                    } else {
                        stringResource(R.string.subs_raw_badge_fallback)
                    },
                )
            }
        }
        if (sub.totalBytes > 0 && expireText != null) {
            Spacer(Modifier.height(4.dp))
            Text(expireText, color = colors.textTertiary, fontSize = 13.sp)
        }
        if (sub.totalBytes > 0) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.bgDeep),
            ) {
                val animated by animateFloatAsState(
                    targetValue = ratio,
                    animationSpec = Motion.smooth(),
                    label = "traffic",
                )
                val barColor = when {
                    ratio >= 0.9f -> colors.danger
                    ratio >= 0.7f -> colors.warning
                    else -> colors.primary
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animated)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(barColor),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.subs_edit),
                color = colors.accent,
                fontSize = 15.sp,
                modifier = Modifier.iosPressable { onEdit() },
            )
            if (sub.url != null) {
                var copied by remember { mutableStateOf(false) }
                LaunchedEffect(copied) {
                    if (copied) {
                        kotlinx.coroutines.delay(1500)
                        copied = false
                    }
                }
                Text(
                    if (copied) stringResource(R.string.subs_copied) else stringResource(R.string.subs_copy),
                    color = colors.accent,
                    fontSize = 15.sp,
                    modifier = Modifier.iosPressable {
                        runCatching {
                            com.interstellar.proxy.InterstellarApplication.clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("subscription", sub.url),
                            )
                        }
                        copied = true
                    },
                )
                Text(
                    stringResource(R.string.subs_update),
                    color = colors.accent,
                    fontSize = 15.sp,
                    modifier = Modifier.iosPressable { onRefresh() },
                )
            }
            Text(
                stringResource(R.string.subs_delete),
                color = colors.danger,
                fontSize = 15.sp,
                modifier = Modifier.iosPressable { onDelete() },
            )
        }
    }
}

@Composable
fun ActionChip(text: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val colors = LocalInterstellarColors.current
    val bg = when {
        primary -> colors.primaryMuted
        danger -> colors.dangerMuted
        else -> colors.bgDeep
    }
    val fg = when {
        primary -> colors.primary
        danger -> colors.danger
        else -> colors.textSecondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .pressableClick { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = fg, fontSize = 12.sp)
    }
}

/** Small pill identifying the retained raw-config format; glows when raw mode is on. */
@Composable
private fun FormatBadge(label: String, active: Boolean, activeHint: String) {
    val colors = LocalInterstellarColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) colors.primaryMuted else colors.bgDeep)
            .border(
                1.dp,
                if (active) colors.primaryBorder else colors.border,
                RoundedCornerShape(50),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            color = if (active) colors.primary else colors.textTertiary,
            fontSize = 10.sp,
        )
        if (active) {
            Spacer(Modifier.width(4.dp))
            Text(activeHint, color = colors.primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EditSubscriptionDialog(
    subscription: SubscriptionRepository.Subscription,
    onSave: (name: String, url: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    var name by remember { mutableStateOf(subscription.name) }
    var url by remember { mutableStateOf(subscription.url ?: "") }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(colors.panelSolid)
                .padding(18.dp),
        ) {
            Text(
                stringResource(R.string.subs_edit_title),
                color = colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.subs_field_name), color = colors.textTertiary, fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    focusedBorderColor = colors.primaryBorder,
                    unfocusedBorderColor = colors.border,
                    cursorColor = colors.primary,
                ),
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.subs_field_url_optional), color = colors.textTertiary, fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    focusedBorderColor = colors.primaryBorder,
                    unfocusedBorderColor = colors.border,
                    cursorColor = colors.primary,
                ),
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionChip(text = stringResource(R.string.subs_cancel)) { onDismiss() }
                Spacer(Modifier.width(10.dp))
                ActionChip(text = stringResource(R.string.subs_save), primary = true) {
                    onSave(name, url)
                }
            }
        }
    }
}

/** 底部悬浮玻璃 toast：订阅更新成功/失败反馈，自动消退。 */
@Composable
private fun SubscriptionsToast(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val toast by viewModel.toast.collectAsState()
    androidx.compose.animation.AnimatedVisibility(
        visible = toast != null,
        enter = slideInVertically(
            tween(240, easing = com.interstellar.proxy.ui.theme.Motion.EaseOutQuart),
        ) { it / 2 } + fadeIn(tween(240)),
        exit = fadeOut(tween(200)) + slideOutVertically(tween(220)) { it / 2 },
        modifier = modifier.padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        val t = toast ?: return@AnimatedVisibility
        val colors = LocalInterstellarColors.current
        val ok = t.kind == com.interstellar.proxy.ui.UiToast.Kind.Success
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(colors.panelSolid)
                .border(
                    1.dp,
                    if (ok) colors.primaryBorder else colors.border,
                    RoundedCornerShape(50),
                )
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (ok) colors.success else colors.danger),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                t.text,
                color = colors.text,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
