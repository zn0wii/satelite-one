package com.interstellar.proxy

import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.content.getSystemService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import com.interstellar.proxy.data.RulesStore
import com.interstellar.proxy.ktx.wrapAppLocale
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.Locale

class InterstellarApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        // wrap BEFORE super so every getString on the application context is
        // localized; AppLanguage reads settings.properties off `base` itself
        super.attachBaseContext(base?.wrapAppLocale())
        application = this
    }

    override fun onCreate() {
        super.onCreate()

        // hev JNI bridge must register on a thread WITH a classloader (main);
        // a first touch from a coroutine IO thread aborts the whole VM
        runCatching { com.interstellar.proxy.core.TProxyService.preload() }

        // pinned language also drives JVM-default formatting / libbox messages
        val pinnedTag = com.interstellar.proxy.data.Settings.appLanguage
            .takeIf { it != com.interstellar.proxy.ktx.AppLanguage.SYSTEM }
        if (pinnedTag != null) {
            // NB: android's java.util.Locale only has the singular forLanguageTag
            runCatching { Locale.setDefault(Locale.forLanguageTag(pinnedTag)) }
        }

        runCatching {
            Libbox.setLocale(Locale.getDefault().toLanguageTag())
        }

        val baseDir = filesDir
        baseDir.mkdirs()
        val workingDir = getExternalFilesDir(null)
        val tempDir = cacheDir
        tempDir.mkdirs()
        workingDir?.mkdirs()

        if (workingDir != null) {
            setupLibbox(baseDir, workingDir, tempDir)
        }

        // warm the built-in rule sets copy in the background
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            runCatching { RulesStore.ensureRules(this@InterstellarApplication) }
            // regenerate the active config if missing (e.g. after a failed
            // generation in a previous run)
            if (com.interstellar.proxy.data.ConfigStore.readActiveConfig() == null) {
                runCatching { com.interstellar.proxy.data.SubscriptionRepository.regenerateActiveConfig() }
            }
        }

        // subscription auto-update schedule
        scheduleAutoUpdate()
    }

    private fun scheduleAutoUpdate() {
        runCatching {
            com.interstellar.proxy.data.UpdateWorker.reschedule(this)
        }
    }

    private fun setupLibbox(baseDir: java.io.File, workingDir: java.io.File, tempDir: java.io.File) {
        Libbox.setup(
            SetupOptions().also {
                it.basePath = baseDir.path
                it.workingPath = workingDir.path
                it.tempPath = tempDir.path
                it.logMaxLines = 3000
            },
        )
    }

    companion object {
        lateinit var application: InterstellarApplication
            private set
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val packageManager by lazy { application.packageManager }
        val powerManager by lazy { application.getSystemService<PowerManager>()!! }
        val notificationManager by lazy { application.getSystemService<NotificationManager>()!! }
        val wifiManager by lazy { application.getSystemService<WifiManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
    }
}
