package com.interstellar.proxy.data

import com.interstellar.proxy.InterstellarApplication
import java.io.File

/**
 * The active sing-box configuration. For now a single generated file;
 * subscription management (P2) regenerates it via ConfigBuilder.
 */
object ConfigStore {
    private const val ACTIVE_CONFIG = "active.json"

    val activeFile: File
        get() = File(InterstellarApplication.application.filesDir, ACTIVE_CONFIG)

    fun readActiveConfig(): String? {
        val file = activeFile
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun writeActiveConfig(content: String) {
        activeFile.parentFile?.mkdirs()
        activeFile.writeText(content)
    }

    /** Drop the active config (all subscriptions removed — nothing to run). */
    fun clear() {
        runCatching { activeFile.delete() }
    }
}
