package com.interstellar.proxy.ktx

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.io.File
import java.util.Properties

/**
 * In-app language preference ("system" | "zh" | "en"), persisted in the same
 * settings.properties [com.interstellar.proxy.data.Settings] uses.
 *
 * Read from the file directly (not via Settings) because it must work in
 * attachBaseContext, before the Settings singleton can safely touch the
 * application reference — and cached so every attach is a map lookup.
 */
object AppLanguage {
    const val SYSTEM = "system"
    const val CHINESE = "zh"
    const val ENGLISH = "en"

    /** Persisted keys Settings.appLanguage writes; kept here to avoid the dependency. */
    private const val FILE_NAME = "settings.properties"
    private const val KEY = "appLanguage"

    @Volatile
    private var cached: String? = null

    /** Live localized Resources for the CURRENT tag — rebuilt after [invalidate]. */
    @Volatile
    private var localizedResources: Resources? = null

    fun current(base: Context): String {
        cached?.let { return it }
        val value = runCatching {
            Properties().apply {
                File(base.filesDir, FILE_NAME).inputStream().use { load(it) }
            }.getProperty(KEY, SYSTEM)
        }.getOrNull() ?: SYSTEM
        cached = value
        return value
    }

    /** The pinned tag, or null when following the system locale. */
    fun pinnedTag(base: Context): String? =
        current(base).takeIf { it != SYSTEM }

    /** After Settings.appLanguage writes a new value the next attach must re-read. */
    fun invalidate() {
        cached = null
        localizedResources = null
    }

    /**
     * Resources for the current tag, following language switches WITHOUT a
     * process restart (the application's own base context is wrapped once at
     * attach and would otherwise serve strings in the previous language).
     */
    fun resourcesOf(base: Context): Resources = synchronized(this) {
        localizedResources?.let { return it }
        val res = pinnedTag(base)?.let { tag ->
            val config = Configuration(base.resources.configuration)
            config.setLocales(LocaleList.forLanguageTags(tag))
            base.createConfigurationContext(config).resources
        } ?: base.resources
        localizedResources = res
        res
    }

    fun getString(base: Context, id: Int, vararg formatArgs: Any): String =
        resourcesOf(base).getString(id, *formatArgs)
}

/**
 * Wrap a component base context with the persisted locale override. Attach in
 * every entry component (Application, MainActivity, services, tile) — each
 * attaches its own context independently. "system" leaves the context alone.
 */
fun Context.wrapAppLocale(): Context {
    val tag = AppLanguage.current(this)
    if (tag == AppLanguage.SYSTEM) return this
    val locales = LocaleList.forLanguageTags(tag)
    if (locales.isEmpty) return this
    val config = Configuration(resources.configuration)
    config.setLocales(locales)
    return createConfigurationContext(config)
}

/**
 * Locale-preserving wrapper for the Compose tree: `stringResource` resolves
 * through [LocalContext], so providing an instance of this at the root swaps
 * every string in place on language change — no activity recreate, no flash.
 * The wrapped base stays the activity itself (startActivity etc. delegate),
 * so it must NOT be used where a real Activity instance is casted — use
 * `LocalView.current.context` for those.
 */
class LocaleContextWrapper(base: Context) : ContextWrapper(base) {
    private val localized: Context? = AppLanguage.pinnedTag(base)?.let { tag ->
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        base.createConfigurationContext(config)
    }

    override fun getResources(): Resources = localized?.resources ?: super.getResources()
    override fun getAssets(): AssetManager = localized?.assets ?: super.getAssets()
}

