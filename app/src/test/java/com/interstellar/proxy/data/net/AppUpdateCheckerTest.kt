package com.interstellar.proxy.data.net

import org.junit.Test

/**
 * Pure logic of the app self-update check: redirect-Location tag extraction
 * and numeric version comparison (lexicographic compare would rank 0.5.10
 * below 0.5.9).
 */
class AppUpdateCheckerTest {

    @Test
    fun `extracts tag from redirect location`() {
        check(
            AppUpdateChecker.extractTag(
                "https://github.com/zn0wii/interstellar-proxy/releases/tag/v0.5.6",
            ) == "v0.5.6",
        )
        check(AppUpdateChecker.extractTag("/zn0wii/interstellar-proxy/releases/tag/v0.5.7") == "v0.5.7")
        check(AppUpdateChecker.extractTag("https://github.com/x/y/releases/tag/0.6.0?a=b") == "0.6.0")
    }

    @Test
    fun `rejects locations without a tag segment`() {
        check(AppUpdateChecker.extractTag("https://github.com/zn0wii/interstellar-proxy/releases") == null)
        check(AppUpdateChecker.extractTag("/releases/tag/") == null)
    }

    @Test
    fun `numeric segment comparison not lexicographic`() {
        check(AppUpdateChecker.isNewer("v0.5.10", "0.5.9"))
        check(!AppUpdateChecker.isNewer("v0.5.9", "0.5.10"))
        check(!AppUpdateChecker.isNewer("v0.5.6", "0.5.6"))
        check(AppUpdateChecker.isNewer("1.0.0", "0.9.9"))
        check(AppUpdateChecker.isNewer("0.6", "v0.5.99"))
        check(!AppUpdateChecker.isNewer("0.5.6-rc1", "0.5.6"))
    }
}
