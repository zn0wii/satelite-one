package com.interstellar.proxy.data

import android.content.Context
import com.interstellar.proxy.InterstellarApplication
import java.io.File

/**
 * Built-in rule sets (bundled in APK assets), copied to filesDir/rules on
 * first use so sing-box can reference them as local rule-sets by path.
 * This avoids the remote download (raw.githubusercontent is unreachable
 * without a proxy — a chicken-and-egg on first start).
 */
object RulesStore {
    private const val DIR_NAME = "rules"

    data class RuleAsset(val assetPath: String, val fileName: String)

    val geoipCn = RuleAsset("rules/geoip-cn.srs", "geoip-cn.srs")
    val geositeCn = RuleAsset("rules/geosite-cn.srs", "geosite-cn.srs")
    val adsAll = RuleAsset("rules/category-ads-all.srs", "category-ads-all.srs")
    val geolocationNotCn = RuleAsset("rules/geosite-geolocation-!cn.srs", "geosite-geolocation-!cn.srs")

    val dir: File
        get() = File(InterstellarApplication.application.filesDir, DIR_NAME).also { it.mkdirs() }

    fun fileOf(asset: RuleAsset): File = File(dir, asset.fileName)

    /**
     * Copies bundled rule assets to filesDir if missing/corrupt.
     * Safe to call repeatedly; returns true when both files are usable.
     */
    fun ensureRules(context: Context): Boolean {
        copyIfNeeded(context, geoipCn)
        copyIfNeeded(context, geositeCn)
        copyIfNeeded(context, adsAll)
        copyIfNeeded(context, geolocationNotCn)
        return fileOf(geoipCn).isFile && fileOf(geositeCn).isFile && fileOf(adsAll).isFile &&
            fileOf(geolocationNotCn).isFile
    }

    private fun copyIfNeeded(context: Context, asset: RuleAsset) {
        val target = fileOf(asset)
        // size>8 sanity: valid srs starts with "SRS\x01" + zlib header
        if (target.isFile && target.length() > 8) return
        runCatching {
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                context.assets.open(asset.assetPath).use { input ->
                    input.copyTo(out)
                }
            }
        }.onFailure {
            target.delete()
        }
    }
}
