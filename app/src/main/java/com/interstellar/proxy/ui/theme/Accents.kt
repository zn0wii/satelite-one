package com.interstellar.proxy.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.interstellar.proxy.data.Settings

/**
 * Macaron accent presets, ported from satelite-proxy's accents.ts.
 * Each preset carries a designer-tuned shade per theme; one accent
 * re-skins the whole UI (primary family + hero glow + ambient wash).
 *
 * Semantic colors (success / warning / danger) never follow the accent.
 */
object Accents {

    data class Preset(val id: String, @androidx.annotation.StringRes val labelRes: Int, val light: Color, val dark: Color)

    val presets = listOf(
        Preset("mint", com.interstellar.proxy.R.string.accent_mint, Color(0xFF1F9A72), Color(0xFF55C89A)),
        Preset("sky", com.interstellar.proxy.R.string.accent_sky, Color(0xFF2C6FAE), Color(0xFF64B5F6)),
        Preset("taro", com.interstellar.proxy.R.string.accent_taro, Color(0xFF7E5CD6), Color(0xFFB49AF0)),
        Preset("peach", com.interstellar.proxy.R.string.accent_peach, Color(0xFFC9556F), Color(0xFFF29CB2)),
        Preset("milk", com.interstellar.proxy.R.string.accent_milk, Color(0xFFB36A1C), Color(0xFFF2B063)),
        Preset("lake", com.interstellar.proxy.R.string.accent_lake, Color(0xFF1E8A96), Color(0xFF5FCBD8)),
    )

    /** Legacy ids (old glow palette / plain "green") mapped onto the new presets. */
    private val legacyMap = mapOf(
        "green" to "mint",
        "matcha" to "mint",
        "lemon" to "milk",
        "coral" to "peach",
    )

    /** Reactive selection, seeded from persisted settings. */
    var selectedId: String by mutableStateOf(normalize(Settings.accentId))
        private set

    fun select(id: String) {
        val normalized = normalize(id)
        selectedId = normalized
        Settings.accentId = normalized
    }

    fun normalize(id: String): String =
        legacyMap[id] ?: id.takeIf { id -> presets.any { it.id == id } } ?: "mint"

    fun preset(id: String): Preset =
        presets.firstOrNull { it.id == normalize(id) } ?: presets.first()

    /** Resolve the accent color against the current theme brightness. */
    fun current(dark: Boolean): Color =
        preset(selectedId).let { if (dark) it.dark else it.light }

    /** Luminance clamp so a bright pick never washes out dark surfaces. */
    fun normalizeForTheme(color: Color, dark: Boolean): Color {
        val l = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
        return when {
            dark && l < 0.28f -> Color(
                color.red + (1f - color.red) * (0.28f - l),
                color.green + (1f - color.green) * (0.28f - l),
                color.blue + (1f - color.blue) * (0.28f - l),
            )

            !dark && l > 0.62f -> Color(
                color.red * (0.62f / l),
                color.green * (0.62f / l),
                color.blue * (0.62f / l),
            )

            else -> color
        }
    }
}
