package com.interstellar.proxy.ui.pages

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.ui.AppEntry
import com.interstellar.proxy.ui.PerAppProxyViewModel
import com.interstellar.proxy.ui.components.GlassCard
import com.interstellar.proxy.ui.components.IosSwitch
import com.interstellar.proxy.ui.theme.LocalInterstellarColors

@Composable
fun PerAppProxyPage(onBack: () -> Unit) {
    val colors = LocalInterstellarColors.current
    val vm: PerAppProxyViewModel = viewModel()
    val apps by vm.apps.collectAsState()
    val loading by vm.loading.collectAsState()
    val enabled by vm.enabled.collectAsState()
    val mode by vm.mode.collectAsState()
    val selected by vm.selected.collectAsState()
    val search by vm.search.collectAsState()
    val showSystemApps by vm.showSystemApps.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = colors.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onBack() }
                    .padding(6.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "分应用代理",
                color = colors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))

        // enable + mode
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用分应用代理", color = colors.text, fontSize = 14.sp)
                        Text(
                            if (mode == Settings.PER_APP_PROXY_INCLUDE) {
                                "仅所选应用经过代理"
                            } else {
                                "所选应用绕过代理,其余走代理"
                            },
                            color = colors.textTertiary,
                            fontSize = 12.sp,
                        )
                    }
                    IosSwitch(
                        checked = enabled,
                        onChange = { vm.setEnabled(it) },
                    )
                }
                if (enabled) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Settings.PER_APP_PROXY_INCLUDE to "白名单模式",
                            Settings.PER_APP_PROXY_EXCLUDE to "黑名单模式",
                        ).forEach { (m, label) ->
                            val isSelected = mode == m
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) colors.primary else colors.bgDeep)
                                    .clickable { vm.setMode(m) }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    color = if (isSelected) colors.onPrimary else colors.textSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已选 ${selected.size} 个应用 · 修改后立即生效(内核热重载)",
                        color = colors.textTertiary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (!enabled) {
            EmptyHint(text = "先启用分应用代理")
            return@Column
        }

        // search + bulk actions
        OutlinedTextField(
            value = search,
            onValueChange = { vm.setSearch(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索应用", color = colors.textTertiary) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
                focusedBorderColor = colors.primaryBorder,
                unfocusedBorderColor = colors.border,
                cursorColor = colors.primary,
            ),
        )
        Spacer(Modifier.height(8.dp))

        val visible = apps
            .asSequence()
            // toggle off: launchable non-system apps (the drawer list);
            // toggle on: every installed package, launcher-less system
            // components included (Google Play 服务 / Google 服务框架 …)
            .filter { if (showSystemApps) true else it.launchable && !it.systemApp }
            .filter {
                search.isBlank() ||
                    it.label.contains(search, true) ||
                    it.packageName.contains(search, true)
            }
            .sortedWith(
                compareByDescending<AppEntry> { it.packageName in selected }
                    .thenByDescending { it.isSelf }
                    .thenBy { it.label.lowercase() },
            )
            .toList()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("显示系统应用", color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            IosSwitch(
                checked = showSystemApps,
                onChange = { vm.setShowSystemApps(it) },
            )
        }
        Spacer(Modifier.height(8.dp))

        val context = LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(text = "自动选择", primary = true) {
                val n = vm.selectCommon()
                Toast.makeText(
                    context,
                    if (n > 0) "已勾选 $n 个常见需代理应用" else "未发现可勾选的应用",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            ActionChip(text = "全选可见") { vm.selectAllVisible(visible) }
            ActionChip(text = "清空") { vm.clearSelection() }
        }
        Text(
            "自动勾选 Google、Instagram、Discord、ChatGPT、Grok 等",
            color = colors.textTertiary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        )
        Spacer(Modifier.height(8.dp))

        when {
            loading -> EmptyHint(text = "正在加载应用列表…")
            visible.isEmpty() -> EmptyHint(text = "没有匹配的应用")
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visible, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            checked = app.packageName in selected,
                            onToggle = { vm.toggle(app.packageName) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalInterstellarColors.current
    GlassCard(modifier = modifier.fillMaxWidth(), contentPadding = 12.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() },
        ) {
            AppIcon(app, size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.label + if (app.isSelf) " (本应用)" else "",
                    color = colors.text,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
                Text(
                    app.packageName,
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            CheckboxDot(checked = checked)
        }
    }
}

@Composable
private fun CheckboxDot(checked: Boolean) {
    val colors = LocalInterstellarColors.current
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (checked) colors.primary else androidx.compose.ui.graphics.Color.Transparent)
            .border(
                width = if (checked) 2.dp else 1.5.dp,
                color = if (checked) colors.primary else colors.border,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.onPrimary),
            )
        }
    }
}

@Composable
private fun AppIcon(app: AppEntry, size: androidx.compose.ui.unit.Dp) {
    val bitmap = app.icon?.toImageBitmap()
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(LocalInterstellarColors.current.bgDeep),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = app.label,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(app.label.take(1), color = LocalInterstellarColors.current.textTertiary, fontSize = 16.sp)
        }
    }
}

private fun Drawable.toImageBitmap(): ImageBitmap? {
    if (this is BitmapDrawable && bitmap != null) {
        return bitmap.asImageBitmap()
    }
    return runCatching {
        val bitmap = android.graphics.Bitmap.createBitmap(
            intrinsicWidth.coerceAtLeast(1),
            intrinsicHeight.coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()
}
