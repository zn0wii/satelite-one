package com.interstellar.proxy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.ui.theme.LocalInterstellarColors
import com.interstellar.proxy.ui.theme.Motion

/** iOS press feedback: subtle scale, haptic, no ripple. */
fun Modifier.iosPressable(onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.55f else 1f,
        animationSpec = tween(100),
        label = "pressAlpha",
    )
    this
        .clickable(interactionSource = interaction, indication = null) {
            onClick()
        }
        .alpha(alpha)
}

/** Inset group card, now rendered as a glass surface. */
@Composable
fun IosCard(
    modifier: Modifier = Modifier,
    /** Optional accent outline (e.g. the active subscription card). */
    border: Color? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val light = colors.bg.luminance() > 0.5f
    Box(
        modifier = modifier
            .then(
                if (light) {
                    Modifier.shadow(
                        6.dp,
                        RoundedCornerShape(16.dp),
                        ambientColor = Color(0x14000000),
                        spotColor = Color(0x1A000000),
                    )
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(16.dp))
            .glassSurface(16.dp, light, colors.panelTop, colors.panelBottom, colors.border)
            .then(
                if (border != null) {
                    Modifier.border(2.dp, border, RoundedCornerShape(16.dp))
                } else {
                    Modifier
                },
            ),
    ) {
        content()
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** iOS settings row: colored icon square + title (+value) + chevron. */
@Composable
fun IosRow(
    icon: ImageVector? = null,
    iconBg: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalInterstellarColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.iosPressable(onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        when {
            leading != null -> {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            icon != null -> {
                Box(
                    modifier = Modifier
                        .size(29.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(iconBg ?: colors.iconBlue),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(12.dp))
            }
        }
        Column(modifier = Modifier.wrapContentWidth()) {
            Text(
                title,
                color = colors.text,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = colors.textTertiary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        value?.let {
            Text(
                it,
                color = colors.textTertiary,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
            )
        }
        trailing?.invoke()
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Text("›", color = colors.textTertiary, fontSize = 20.sp, fontWeight = FontWeight.Normal)
        }
    }
}

@Composable
fun IosHairline(startInset: Dp = 16.dp) {
    val colors = LocalInterstellarColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(0.33.dp)
            .background(colors.border),
    )
}

@Composable
fun IosSectionLabel(text: String) {
    val colors = LocalInterstellarColors.current
    Text(
        text,
        color = colors.textTertiary,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, bottom = 7.dp),
    )
}

@Composable
fun IosToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val colors = LocalInterstellarColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = colors.text, fontSize = 17.sp)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = colors.textTertiary, fontSize = 13.sp)
            }
        }
        IosSwitch(checked = checked, onChange = { if (enabled) onChange(it) })
    }
}

/** iOS section footer caption. */
@Composable
fun IosSectionFooter(text: String, modifier: Modifier = Modifier) {
    val colors = LocalInterstellarColors.current
    Text(
        text,
        color = colors.textTertiary,
        fontSize = 12.sp,
        modifier = modifier.padding(start = 30.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
    )
}

/**
 * Wide-knob switch (new iOS style): the knob is a rounded rectangle
 * taking ~half the track width and slides left(off)/right(on).
 */
@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalInterstellarColors.current
    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.primary else colors.bgDeep,
        animationSpec = tween(180),
        label = "switchTrack",
    )
    val knobProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = 380f,
        ),
        label = "switchKnob",
    )
    val width = 54.dp
    val height = 32.dp
    val padding = 3.dp
    val knobWidth = (width - padding * 2) * 0.52f
    val travel = width - padding * 2 - knobWidth

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(CircleShape)
            .background(trackColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = padding + travel * knobProgress)
                .width(knobWidth)
                .height(height - padding * 2)
                .shadow(1.5.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(Color.White),
        )
    }
}
