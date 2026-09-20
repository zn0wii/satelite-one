package com.interstellar.proxy.data

/**
 * Apps that typically need a proxy from mainland networks.
 * Matching is prefix / exact package first, then label keywords for
 * regional / renamed packages. Only installed apps from the loaded
 * list are selected; companions (GMS / Play) are added if present.
 */
object CommonProxyApps {

    /** Not launchable, but Google apps break in include-mode without them. */
    val companions = listOf(
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.gsf.login",
        "com.android.vending",
        // system download provider — executes Play Store downloads
        "com.android.providers.downloads",
    )

    fun matches(packageName: String, label: String): Boolean {
        val pkg = packageName.lowercase()
        if (isExcluded(pkg)) return false
        if (prefixes.any { prefixMatch(pkg, it) }) return true
        if (pkg in exact) return true
        val name = label.lowercase()
        return keywords.any { name.contains(it) }
    }

    private fun prefixMatch(pkg: String, prefix: String): Boolean =
        if (prefix.endsWith('.')) pkg.startsWith(prefix)
        else pkg == prefix || pkg.startsWith("$prefix.")

    private fun isExcluded(pkg: String): Boolean {
        if (pkg in excludeExact) return true
        return excludeContains.any { pkg.contains(it) }
    }

    // Namespaces: whole vendor is usually blocked. Trailing dot = prefix only.
    private val prefixes = listOf(
        // Google / Chrome
        "com.google.android.",
        "com.google.earth",
        "com.android.chrome",
        "com.chrome.",
        // Meta
        "com.instagram.",
        "com.facebook.",
        "com.whatsapp",
        "com.oculus.",
        // X / Grok
        "com.twitter.",
        "com.x.android",
        "ai.x.",
        "com.xai.",
        // OpenAI / Anthropic / other AI
        "com.openai.",
        "com.anthropic.",
        "ai.perplexity.",
        "com.microsoft.copilot",
        "com.microsoft.bing",
        "ai.character.",
        "com.poe.android",
        // Discord / Reddit / Snap / Pinterest / LinkedIn
        "com.discord",
        "com.reddit.",
        "com.snapchat.",
        "com.pinterest",
        "com.linkedin.",
        // Telegram family
        "org.telegram.",
        "org.thunderdog.challegram",
        "tw.nekomimi.nekogram",
        // Signal / Line
        "org.thoughtcrime.securesms",
        "jp.naver.line.android",
        // TikTok international (not Douyin)
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        // Streaming
        "com.spotify.",
        "com.netflix.",
        "com.disney.disneyplus",
        "com.hbo.",
        "com.wbd.stream",
        "tv.twitch.",
        "com.twitch.",
        "com.soundcloud.",
        "com.vimeo.",
        "com.apple.android.music",
        "com.amazon.avod.",
        "com.amazon.mShop.",
        // Browsers (western)
        "org.mozilla.",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.opera.",
        "com.vivaldi.",
        "com.duckduckgo.",
        // Dev / productivity
        "com.github.android",
        "com.gitlab.android",
        "com.slack",
        "us.zoom.videomeetings",
        "com.skype.",
        "com.microsoft.teams",
        "com.microsoft.office.outlook",
        "com.notion.",
        "com.figma.",
        "com.dropbox.",
        "mega.privacy.android.app",
        "ch.protonmail.",
        "me.proton.",
        "com.protonvpn.",
        "org.wikipedia",
        "com.valvesoftware.android.steam.community",
        "com.epicgames.",
        "com.ebay.",
        "com.paypal.",
        "xyz.blueskyweb.app",
        "jp.pxv.android",
        "com.duolingo",
        "org.coursera.android",
        "com.udemy.android",
        "com.medium.reader",
        "com.quora.android",
        "com.patreon.",
        "com.replit.app",
        "com.canva.editor",
        "com.stackexchange.",
    )

    private val exact = setOf(
        "com.android.vending",
        "org.wikipedia",
        "com.discord",
        "com.pinterest",
        "ai.x.grok",
        "com.xai.grok",
        "com.openai.chatgpt",
        "com.anthropic.claude",
        "com.google.android.apps.bard",
    )

    private val keywords = listOf(
        "gmail", "youtube", "chrome", "instagram", "threads", "facebook",
        "messenger", "whatsapp", "discord", "telegram", "twitter", "reddit",
        "spotify", "netflix", "twitch", "chatgpt", "grok", "claude", "gemini",
        "perplexity", "copilot", "openai", "signal", "snapchat", "pinterest",
        "linkedin", "github", "wikipedia", "dropbox", "proton", "notion",
        "slack", "zoom", "steam", "bluesky", "pixiv", "soundcloud", "duolingo",
        "coursera", "udemy", "paypal", "ebay", "vimeo", "outlook", "skype",
        "flickr", "patreon", "medium", "quora", "canva", "figma", "replit",
        "huggingface", "notebooklm", "bard", "tiktok", "disney+",
        "prime video", "hbo", "loki.saver", "loki saver", "谷歌", "推特", "电报",
    )

    private val excludeContains = listOf(
        "inputmethod",
        "permissioncontroller",
        "packageinstaller",
        "webview",
        "overlay",
        "captiveportallogin",
        "setupwizard",
        "auto_generated_rro",
    )

    private val excludeExact = setOf(
        "com.google.android.apps.wellbeing",
        "com.google.android.apps.restore",
        "com.google.android.apps.nbu.paisa.user",
        "com.google.android.apps.walletnfcrel",
    )
}
