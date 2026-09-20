package com.interstellar.proxy.ui

import android.app.Application
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.data.CommonProxyApps
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.utils.CommandTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSelf: Boolean = false,
    val systemApp: Boolean = false,
    /** Has a launcher entry; false for background system components (GMS, GSF…). */
    val launchable: Boolean = true,
)

/**
 * Per-app proxy state: launchable app list, selection and persistence.
 * Applying changes hot-reloads the running core (OverrideOptions rebuild).
 */
class PerAppProxyViewModel(application: Application) : AndroidViewModel(application) {

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _enabled = MutableStateFlow(Settings.perAppProxyEnabled)
    val enabled: StateFlow<Boolean> = _enabled

    private val _mode = MutableStateFlow(Settings.perAppProxyMode)
    val mode: StateFlow<Int> = _mode

    private val _selected = MutableStateFlow(Settings.perAppProxyList)
    val selected: StateFlow<Set<String>> = _selected

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search

    private val _showSystemApps = MutableStateFlow(Settings.perAppProxyShowSystemApps)
    val showSystemApps: StateFlow<Boolean> = _showSystemApps

    init {
        viewModelScope.launch {
            _apps.value = loadApps()
            _loading.value = false
        }
    }

    private suspend fun loadApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val context = InterstellarApplication.application
        val pm = context.packageManager
        val self = context.packageName
        val launcherApps = context.getSystemService(LauncherApps::class.java)

        // packages with a launcher entry — the default (toggle-off) list
        val launchable = mutableSetOf(self)
        // LauncherApps lists launchable apps for the current user without
        // needing QUERY_ALL_PACKAGES on Android 11+; fall back to PM queries.
        runCatching {
            launcherApps?.getActivityList(null, Process.myUserHandle())?.forEach { info ->
                launchable.add(info.componentName.packageName)
            }
        }.onFailure {
            pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { info ->
                if (pm.getLaunchIntentForPackage(info.packageName) != null || info.packageName == self) {
                    launchable.add(info.packageName)
                }
            }
        }

        // every installed package — QUERY_ALL_PACKAGES is declared, so this
        // also covers launcher-less components a whitelist needs but no app
        // drawer ever shows (Google Play 服务 / Google 服务框架 / the system
        // download provider), including disabled ones on CN ROMs
        val installed = mutableSetOf(self)
        runCatching {
            pm.getInstalledPackages(0).forEach { pi ->
                pi.applicationInfo?.let { installed.add(it.packageName) }
            }
        }

        (installed + launchable).mapNotNull { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                AppEntry(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString().ifBlank { pkg },
                    icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                    isSelf = pkg == self,
                    systemApp = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                    launchable = pkg in launchable,
                )
            }.getOrNull()
        }.sortedWith(compareByDescending<AppEntry> { it.isSelf }.thenBy { it.label.lowercase() })
    }

    fun setSearch(value: String) {
        _search.value = value
    }

    fun setShowSystemApps(value: Boolean) {
        _showSystemApps.value = value
        Settings.perAppProxyShowSystemApps = value
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        Settings.perAppProxyEnabled = value
        apply()
    }

    fun setMode(mode: Int) {
        _mode.value = mode
        Settings.perAppProxyMode = mode
        apply()
    }

    fun toggle(packageName: String) {
        val current = _selected.value
        _selected.value = if (packageName in current) current - packageName else current + packageName
        Settings.perAppProxyList = _selected.value
        apply()
    }

    fun selectAllVisible(visible: List<AppEntry>) {
        _selected.value = (_selected.value + visible.map { it.packageName }).toSet()
        Settings.perAppProxyList = _selected.value
        apply()
    }

    /**
     * Check common apps that typically need a proxy (Google, Instagram,
     * Discord, ChatGPT, Grok, …). Unions with the current selection.
     * @return number of newly checked packages
     */
    fun selectCommon(): Int {
        val matched = _apps.value
            .asSequence()
            .filter { !it.isSelf && CommonProxyApps.matches(it.packageName, it.label) }
            .map { it.packageName }
            .toMutableSet()
        val pm = getApplication<Application>().packageManager
        CommonProxyApps.companions.forEach { pkg ->
            if (runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess) {
                matched += pkg
            }
        }
        val added = matched - _selected.value
        if (added.isEmpty()) return 0
        _selected.value = _selected.value + matched
        Settings.perAppProxyList = _selected.value
        apply()
        return added.size
    }

    fun clearSelection() {
        _selected.value = emptySet()
        Settings.perAppProxyList = emptySet()
        apply()
    }

    /**
     * Persisted already; hot-apply to the RUNNING core per kind. sing-box
     * rebuilds OverrideOptions via serviceReload; the sidecar cores carry
     * per-app in the VPN builder, which their applyConfig re-establishes
     * (mihomo force-respawns on per-app changes, Xray always respawns).
     */
    private fun apply() {
        viewModelScope.launch(Dispatchers.IO) {
            when (com.interstellar.proxy.data.Settings.coreKind) {
                com.interstellar.proxy.core.CoreKind.SINGBOX ->
                    runCatching { CommandTarget.standaloneClient().serviceReload() }

                com.interstellar.proxy.core.CoreKind.MIHOMO ->
                    runCatching {
                        com.interstellar.proxy.core.MihomoCore.Holder.instance?.refreshFromConfigStore()
                    }

                com.interstellar.proxy.core.CoreKind.XRAY ->
                    runCatching {
                        com.interstellar.proxy.core.XrayCore.Holder.instance?.restartFromConfigStore()
                    }
            }
        }
    }
}
