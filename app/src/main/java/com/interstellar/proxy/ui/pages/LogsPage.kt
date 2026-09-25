package com.interstellar.proxy.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.R
import com.interstellar.proxy.ui.LogsViewModel
import com.interstellar.proxy.ui.theme.LocalInterstellarColors
import com.interstellar.proxy.ui.LogLine

@Composable
fun LogsPage(viewModel: LogsViewModel) {
    val colors = LocalInterstellarColors.current
    val logs by viewModel.logs.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.logs_title),
                color = colors.text,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (!connected) {
                Text(stringResource(R.string.logs_not_running), color = colors.textTertiary, fontSize = 12.sp)
            }
            Spacer(Modifier.padding(4.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.bgDeep)
                        .clickable {
                            val text = logs.joinToString("\n") { "[${levelName(it.level)}] ${it.message}" }
                            if (text.isNotBlank()) {
                                com.interstellar.proxy.InterstellarApplication.clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText("logs", text.take(380_000)),
                                )
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(stringResource(R.string.logs_copy), color = colors.textSecondary, fontSize = 12.sp)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.bgDeep)
                        .clickable { viewModel.clearLogs() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(stringResource(R.string.logs_clear), color = colors.textSecondary, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (logs.isEmpty()) {
            EmptyHint(
                text = if (connected) stringResource(R.string.logs_waiting) else stringResource(R.string.logs_start_hint),
            )
            return@Column
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(logs.size) { index ->
                val entry = logs[index]
                Text(
                    text = formatLog(entry),
                    color = logColor(entry.level),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

private fun formatLog(entry: LogLine): String {
    return "[${levelName(entry.level)}] ${entry.message}"
}

private fun levelName(level: Int): String = when (level) {
    0 -> "PANIC"
    1 -> "FATAL"
    2 -> "ERROR"
    3 -> "WARN"
    4 -> "INFO"
    5 -> "DEBUG"
    6 -> "TRACE"
    else -> "LOG"
}

@Composable
private fun logColor(level: Int): androidx.compose.ui.graphics.Color {
    val colors = LocalInterstellarColors.current
    return when (level) {
        0, 1, 2 -> colors.danger
        3 -> colors.warning
        4 -> colors.textSecondary
        else -> colors.textTertiary
    }
}
