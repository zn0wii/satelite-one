package com.interstellar.proxy.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.interstellar.proxy.R
import com.interstellar.proxy.data.NodeMatcher
import com.interstellar.proxy.data.SimpleRouteRule
import com.interstellar.proxy.data.config.ConfigBuilder

/**
 * Display-layer translation for FUNCTIONAL names that must stay byte-stable
 * in generated configs and persisted settings: group tags (手动选择 / auto /
 * smart / "🇭🇰 香港 · 自动"), country codes, region labels, rule actions.
 *
 * The internal name is never localized — only its rendering. Render group
 * tags / countries through these helpers instead of raw strings.
 */
object LocalizedNames {

    /** Region-group dedupe suffixes ConfigBuilder.uniqueTag appends. */
    private const val SUFFIX_AUTO = " · 自动"
    private const val SUFFIX_AUTO_N = " · 自动 ("

    @StringRes
    fun countryRes(code: String?): Int? = when (code?.uppercase()) {
        "CN" -> R.string.country_cn
        "HK" -> R.string.country_hk
        "TW" -> R.string.country_tw
        "JP" -> R.string.country_jp
        "KR" -> R.string.country_kr
        "SG" -> R.string.country_sg
        "US" -> R.string.country_us
        "CA" -> R.string.country_ca
        "GB" -> R.string.country_gb
        "DE" -> R.string.country_de
        "FR" -> R.string.country_fr
        "NL" -> R.string.country_nl
        "RU" -> R.string.country_ru
        "AU" -> R.string.country_au
        "IN" -> R.string.country_in
        "TR" -> R.string.country_tr
        "BR" -> R.string.country_br
        "TH" -> R.string.country_th
        "VN" -> R.string.country_vn
        "MY" -> R.string.country_my
        "PH" -> R.string.country_ph
        "ID" -> R.string.country_id
        "IT" -> R.string.country_it
        "ES" -> R.string.country_es
        "SE" -> R.string.country_se
        "CH" -> R.string.country_ch
        "AE" -> R.string.country_ae
        "AR" -> R.string.country_ar
        else -> null
    }

    fun countryName(context: Context, code: String?): String =
        countryRes(code)?.let { context.getString(it) } ?: (code ?: "")

    /** Any group/outbound tag → localized display name (unknown tags pass through). */
    fun groupName(context: Context, tag: String): String = when {
        tag == ConfigBuilder.GROUP_TAG -> context.getString(R.string.group_manual)
        tag == ConfigBuilder.AUTO_TAG -> context.getString(R.string.group_auto)
        tag == ConfigBuilder.SMART_TAG -> context.getString(R.string.group_smart)
        else -> translateRegionTag(context, tag) ?: tag
    }

    /** NodeMatcher.regionLabel, localized (节点页分组头). */
    fun regionLabel(context: Context, tag: String): String {
        if (NodeMatcher.isInfoTag(tag)) return context.getString(R.string.region_info)
        val region = NodeMatcher.regionOf(tag) ?: return context.getString(R.string.region_other)
        return region.flag + " " + countryName(context, region.id)
    }

    /**
     * Region urltest tags are "🇭🇰 香港" plus an optional dedupe suffix
     * (" · 自动" / " · 自动 (2)") — translate base and suffix separately.
     */
    private fun translateRegionTag(context: Context, tag: String): String? {
        for (region in NodeMatcher.REGIONS) {
            val base = "${region.flag} ${region.name}"
            val localizedBase = region.flag + " " + countryName(context, region.id)
            when {
                tag == base -> return localizedBase
                tag.startsWith(base + SUFFIX_AUTO_N) -> {
                    val n = tag.removePrefix(base + SUFFIX_AUTO_N).removeSuffix(")")
                    return localizedBase + context.getString(R.string.region_suffix_auto_n, n)
                }
                tag.startsWith(base + SUFFIX_AUTO) ->
                    return localizedBase + context.getString(R.string.region_suffix_auto)
            }
        }
        return null
    }

    @StringRes
    fun ruleActionRes(action: SimpleRouteRule.Action): Int = when (action) {
        SimpleRouteRule.Action.DIRECT -> R.string.rule_action_direct
        SimpleRouteRule.Action.PROXY -> R.string.rule_action_proxy
        SimpleRouteRule.Action.NODE -> R.string.rule_action_node
    }

    fun smartPhaseText(context: Context, phase: SmartSwitchEngine.SmartPhase): String =
        with(context) {
            when (phase) {
                SmartSwitchEngine.SmartPhase.Idle -> getString(R.string.smart_phase_idle)
                SmartSwitchEngine.SmartPhase.Patrolling -> getString(R.string.smart_phase_patrolling)
                is SmartSwitchEngine.SmartPhase.PatrolError ->
                    getString(R.string.smart_phase_patrol_error, phase.brief)
                is SmartSwitchEngine.SmartPhase.Healthy ->
                    getString(R.string.smart_phase_healthy, phase.delayMs)
                SmartSwitchEngine.SmartPhase.PingScan -> getString(R.string.smart_phase_ping_scan)
                SmartSwitchEngine.SmartPhase.RealTest -> getString(R.string.smart_phase_real_test)
                SmartSwitchEngine.SmartPhase.Cooldown -> getString(R.string.smart_phase_cooldown)
                is SmartSwitchEngine.SmartPhase.Switched ->
                    getString(R.string.smart_phase_switched, phase.delayMs)
                is SmartSwitchEngine.SmartPhase.Keep ->
                    getString(R.string.smart_phase_keep, phase.delayMs)
                SmartSwitchEngine.SmartPhase.Failed -> getString(R.string.smart_phase_failed)
            }
        }

    fun smartAlertText(context: Context, alert: SmartSwitchEngine.SmartAlert): String =
        with(context) {
            when (alert) {
                SmartSwitchEngine.SmartAlert.NoPool -> getString(R.string.smart_alert_no_pool)
                SmartSwitchEngine.SmartAlert.NoCandidates -> getString(R.string.smart_alert_no_candidates)
                SmartSwitchEngine.SmartAlert.NoQualified -> getString(R.string.smart_alert_no_qualified)
                is SmartSwitchEngine.SmartAlert.RoundTooLong ->
                    getString(R.string.smart_alert_round_too_long, alert.seconds)
            }
        }
}

/** Composable flavor of [LocalizedNames.groupName]. */
@Composable
fun localizedGroupName(tag: String): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return LocalizedNames.groupName(context, tag)
}

/** Composable flavor of [LocalizedNames.regionLabel]. */
@Composable
fun localizedRegionLabel(tag: String): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return LocalizedNames.regionLabel(context, tag)
}

/** Composable flavor of [LocalizedNames.countryName]; null codes stay null. */
@Composable
fun localizedCountryName(code: String?): String? =
    code?.let { LocalizedNames.countryName(androidx.compose.ui.platform.LocalContext.current, it) }

/** Composable flavor of the simple-rule action label. */
@Composable
fun SimpleRouteRule.Action.localizedLabel(): String = stringResource(LocalizedNames.ruleActionRes(this))
