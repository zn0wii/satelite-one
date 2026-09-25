package com.interstellar.proxy.bg

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.interstellar.proxy.data.ConfigStore
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.ktx.wrapAppLocale

/**
 * Quick-settings tile: one-tap VPN toggle.
 * Tracks last-known state via Settings so the tile icon stays truthful.
 */
class QuickTileService : TileService() {

    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base?.wrapAppLocale())
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val running = Settings.tileActive
        when {
            running -> {
                BoxService.stop()
                Settings.tileActive = false
            }

            else -> {
                if (android.net.VpnService.prepare(this) != null) {
                    // permission not granted yet — open the app to complete setup
                    launchMain(
                        android.content.Intent(this, com.interstellar.proxy.MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    return
                }
                if (ConfigStore.readActiveConfig() == null) {
                    launchMain(
                        android.content.Intent(this, com.interstellar.proxy.MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    return
                }
                BoxService.start()
                Settings.tileActive = true
            }
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val active = Settings.tileActive
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(if (active) com.interstellar.proxy.R.string.status_started else com.interstellar.proxy.R.string.tile_disconnected)
        tile.updateTile()
    }

    private fun launchMain(intent: android.content.Intent) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivity(intent)
            }
        }
    }
}
