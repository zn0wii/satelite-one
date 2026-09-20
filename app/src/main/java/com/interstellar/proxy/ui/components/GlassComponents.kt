package com.interstellar.proxy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.constant.Status
import com.interstellar.proxy.ui.theme.LocalInterstellarColors
import com.interstellar.proxy.ui.theme.Motion

/**
 * Glass material, ported from satelite-proxy's `.glass` recipe:
 * translucent gradient fill lit from the upper-left, hairline border,
 * pill-safe rounded corners; soft shadow on the light theme only.
 */
fun Modifier.glassSurface(
    cornerRadius: Dp,
    light: Boolean,
    fillTop: Color,
    fillBottom: Color,
    borderColor: Color,
): Modifier = drawBehind {
    val corner = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
    drawRoundRect(
        brush = Brush.linearGradient(listOf(fillTop, fillBottom)),
        size = size,
        cornerRadius = corner,
    )
    // 135° reflection — the surface is "lit" from the upper-left
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(Color.White.copy(alpha = if (light) 0.30f else 0.09f), Color.Transparent),
            start = Offset.Zero,
            end = Offset(size.width * 0.9f, size.height * 0.75f),
        ),
        size = size,
        cornerRadius = corner,
    )
}.border(
    width = 1.dp,
    color = borderColor,
    shape = RoundedCornerShape(cornerRadius),
)

/** Glass panel card — the base surface of the console UI. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    contentPadding: Dp = 14.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val light = 0.2126f * colors.bg.red + 0.7152f * colors.bg.green + 0.0722f * colors.bg.blue > 0.5f
    Box(
        modifier = modifier
            .then(
                if (light) {
                    Modifier.shadow(
                        6.dp,
                        RoundedCornerShape(cornerRadius),
                        ambientColor = Color(0x14000000),
                        spotColor = Color(0x1A000000),
                    )
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(cornerRadius))
            .glassSurface(cornerRadius, light, colors.panelTop, colors.panelBottom, colors.border)
            .then(
                if (onClick != null) Modifier.pressableClick(onClick) else Modifier,
            ),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

enum class GlassButtonStyle { Primary, Secondary, Danger }

/**
 * Capsule action button — outlined with a very light tinted fill:
 * accent at ~13% under a colored hairline, accent text. The secondary
 * action stays neutral glass.
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GlassButtonStyle = GlassButtonStyle.Secondary,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = LocalInterstellarColors.current
    val light = 0.2126f * colors.bg.red + 0.7152f * colors.bg.green + 0.0722f * colors.bg.blue > 0.5f
    val accent = when (style) {
        GlassButtonStyle.Primary -> colors.primary
        GlassButtonStyle.Danger -> colors.danger
        GlassButtonStyle.Secondary -> colors.text
    }
    val fg = when (style) {
        GlassButtonStyle.Primary -> colors.primary
        GlassButtonStyle.Danger -> colors.danger
        GlassButtonStyle.Secondary -> colors.text
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clip(RoundedCornerShape(50))
            .then(
                when (style) {
                    GlassButtonStyle.Secondary ->
                        Modifier.glassSurface(50.dp, light, colors.panelTop, colors.panelBottom, colors.border)

                    else -> Modifier
                        // very light tinted fill + colored hairline
                        .background(accent.copy(alpha = 0.13f))
                        .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(50))
                },
            )
            .defaultMinSize(minWidth = 104.dp, minHeight = 48.dp)
            .pressableClick { if (enabled) onClick() }
            .padding(horizontal = 24.dp, vertical = 13.dp),
    ) {
        leading?.invoke()
        if (leading != null) Spacer(Modifier.width(7.dp))
        Text(
            text,
            color = fg,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            maxLines = 1,
        )
    }
}

/** RUN / OFF / CONNECTING capsule with a breathing dot, satelite's status pill. */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val colors = LocalInterstellarColors.current
    val light = 0.2126f * colors.bg.red + 0.7152f * colors.bg.green + 0.0722f * colors.bg.blue > 0.5f
    // breathing halo only ticks while active — no idle per-frame redraws
    val breath = if (active) {
        rememberInfiniteTransition(label = "pillBreath").animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse),
            label = "pillBreathAlpha",
        ).value
    } else {
        0f
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .glassSurface(50.dp, light, colors.panelTop, colors.panelBottom, colors.border)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer { alpha = if (active) breath * 0.5f else 0f }
                    .clip(CircleShape)
                    .background(color),
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
        )
    }
}

/**
 * Dual-area live traffic chart (satelite's sparkline): download as a
 * filled green area, upload as a stroked red line on top.
 */
@Composable
fun TrafficSparkline(
    history: List<Pair<Long, Long>>,
    modifier: Modifier = Modifier,
    downColor: Color,
    upColor: Color,
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        if (history.isEmpty()) return@Canvas
        val n = history.size
        val maxV = history.maxOf { maxOf(it.first, it.second) }.toFloat().coerceAtLeast(1f)
        val stepX = size.width / 59f.coerceAtLeast((n - 1).toFloat()).coerceAtLeast(1f)
        val w = stepX * (n - 1)

        fun points(select: (Pair<Long, Long>) -> Long): List<Offset> = history.mapIndexed { i, sample ->
            val v = select(sample).toFloat() / maxV
            Offset(size.width - w + i * stepX, size.height * (1f - v * 0.92f) - size.height * 0.04f)
        }

        val down = points { it.first }
        val up = points { it.second }

        // download: filled area down to the baseline
        if (down.size >= 2) {
            val area = Path().apply {
                moveTo(down.first().x, size.height)
                down.forEach { lineTo(it.x, it.y) }
                lineTo(down.last().x, size.height)
                close()
            }
            drawPath(
                area,
                brush = Brush.verticalGradient(
                    listOf(downColor.copy(alpha = 0.40f), downColor.copy(alpha = 0.02f)),
                ),
            )
            drawPath(
                Path().apply {
                    moveTo(down.first().x, down.first().y)
                    down.drop(1).forEach { lineTo(it.x, it.y) }
                },
                color = downColor.copy(alpha = 0.95f),
                style = Stroke(width = 2f),
            )
        }
        // upload: thin line above
        if (up.size >= 2) {
            drawPath(
                Path().apply {
                    moveTo(up.first().x, up.first().y)
                    up.drop(1).forEach { lineTo(it.x, it.y) }
                },
                color = upColor.copy(alpha = 0.85f),
                style = Stroke(width = 1.6f),
            )
        }
    }
}

/**
 * Floating glass dock (satelite's capsule navbar, bottom-docked for phones):
 * a frosted thumb that slides between icon+label items.
 */
data class DockItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconSelected: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
fun GlassDock(
    items: List<DockItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalInterstellarColors.current
    val light = 0.2126f * colors.bg.red + 0.7152f * colors.bg.green + 0.0722f * colors.bg.blue > 0.5f
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(50))
            .glassSurface(50.dp, light, colors.panelTop, colors.panelBottom, colors.border),
    ) {
        val itemWidth = maxWidth / items.size
        val thumbX by androidx.compose.animation.core.animateDpAsState(
            targetValue = itemWidth * selected.coerceIn(0, items.size - 1),
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f),
            label = "dockThumb",
        )

        // frosted sliding thumb
        Box(
            modifier = Modifier
                .offset(x = thumbX)
                .width(itemWidth)
                .fillMaxHeight()
                .padding(5.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.surfaceHigh)
                .border(1.dp, colors.border, RoundedCornerShape(50)),
        )

        Row(modifier = Modifier.fillMaxSize()) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selected
                val fg by animateColorAsState(
                    targetValue = if (isSelected) colors.primary else colors.textTertiary,
                    animationSpec = tween(Motion.DURATION_MEDIUM, easing = Motion.Ease),
                    label = "dockFg",
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                ) {
                    Icon(
                        imageVector = if (isSelected) item.iconSelected else item.icon,
                        contentDescription = item.label,
                        tint = fg,
                        modifier = Modifier.size(21.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.label,
                        color = fg,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Page header per the reference design: a small uppercase kicker
 * above a big bold title, with an optional trailing action.
 */
@Composable
fun PageHeader(
    kicker: String,
    title: String,
    modifier: Modifier = Modifier,
    /** Small text riding the title's baseline (e.g. the active subscription). */
    titleNote: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalInterstellarColors.current
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                kicker,
                color = colors.textTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    title,
                    color = colors.text,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (!titleNote.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        titleNote,
                        color = colors.textTertiary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(bottom = 5.dp),
                    )
                }
            }
        }
        trailing?.invoke()
    }
}

/**
 * Classic orbit hero (satelite's `classic` style): concentric rings,
 * a diamond core and orbiting satellite dots. The whole thing spins
 * while running; connecting pulses in info blue; stopped stays dim.
 */
@Composable
fun OrbitHero(
    status: Status,
    modifier: Modifier = Modifier,
    heroSize: Dp = 200.dp,
) {
    val colors = LocalInterstellarColors.current
    val strokeColor by animateColorAsState(
        targetValue = when (status) {
            Status.Started -> colors.primary
            Status.Starting -> colors.accent
            Status.Stopping -> colors.warning
            Status.Stopped -> colors.textTertiary
        },
        animationSpec = tween(360),
        label = "orbitColor",
    )
    val running = status == Status.Started
    // orbit sweep + pulse only tick while the hero is live; a stopped orbit is static
    val angle: Float
    val pulse: Float
    if (running || status == Status.Starting) {
        val idle = rememberInfiniteTransition(label = "orbit")
        angle = idle.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(11_000, easing = LinearEasing)),
            label = "orbitAngle",
        ).value
        pulse = idle.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
            label = "orbitPulse",
        ).value
    } else {
        angle = 0f
        pulse = 1f
    }
    val orbitScale by animateFloatAsState(
        targetValue = if (status == Status.Starting) pulse else if (running) 1f + (pulse - 1f) * 0.25f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f),
        label = "orbitScale",
    )

    androidx.compose.foundation.Canvas(modifier = modifier.size(heroSize)) {
        val c = center
        val r = size.minDimension / 2f
        val glowR = r * 0.95f
        drawCircle(
            brush = Brush.radialGradient(
                listOf(strokeColor.copy(alpha = 0.30f), Color.Transparent),
                center = c,
                radius = glowR,
            ),
            radius = glowR,
            center = c,
        )
        scale(orbitScale, orbitScale, pivot = c) {
            // rings
            listOf(0.94f, 0.66f, 0.40f).forEachIndexed { i, f ->
                drawCircle(
                    color = strokeColor.copy(alpha = (0.34f - i * 0.09f).coerceAtLeast(0.10f)),
                    radius = r * f,
                    center = c,
                    style = Stroke(width = 1.6f - i * 0.3f),
                )
            }
            // diamond core
            rotate(if (running) angle * 0.5f else 0f, pivot = c) {
                val d = r * 0.115f
                drawPath(
                    Path().apply {
                        moveTo(c.x, c.y - d)
                        lineTo(c.x + d, c.y)
                        lineTo(c.x, c.y + d)
                        lineTo(c.x - d, c.y)
                        close()
                    },
                    color = strokeColor,
                )
            }
            // satellites
            if (running) {
                rotate(angle, pivot = c) {
                    drawCircle(color = strokeColor, radius = r * 0.032f, center = Offset(c.x + r * 0.94f, c.y))
                }
                rotate(-angle * 0.62f + 140f, pivot = c) {
                    drawCircle(
                        color = strokeColor.copy(alpha = 0.75f),
                        radius = r * 0.024f,
                        center = Offset(c.x + r * 0.66f, c.y),
                    )
                }
            } else {
                drawCircle(color = strokeColor, radius = r * 0.032f, center = Offset(c.x + r * 0.94f, c.y))
            }
        }
    }
}
