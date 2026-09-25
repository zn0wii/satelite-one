package com.interstellar.proxy.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StatusMessage
import com.interstellar.proxy.MainActivity
import com.interstellar.proxy.R
import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.constant.Action
import com.interstellar.proxy.constant.Status
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.utils.CommandClient
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.withContext

class ServiceNotification(private val status: MutableLiveData<Status>, private val service: Service) :
    BroadcastReceiver(),
    CommandClient.Handler {
    companion object {
        private const val notificationId = 1
        private const val notificationChannel = "service"
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        fun checkPermission(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true
            }
            return InterstellarApplication.notification.areNotificationsEnabled()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val commandClient =
        CommandClient(GlobalScope, CommandClient.ConnectionType.Status, this, localOnly = true)
    private var receiverRegistered = false

    /**
     * Set by close(): the service notification is gone. A late traffic
     * callback (mihomo poller races core shutdown — trafficJob is cancelled
     * only after close) must not re-post it via NotificationManager.notify —
     * that re-posted notification is no longer bound to the foreground
     * service and survives stopSelf() as a stale "still connected" one.
     */
    @Volatile
    private var released = false

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(service, notificationChannel).setShowWhen(false).setOngoing(true)
            .setContentTitle(service.getString(R.string.app_tagline)).setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_stat)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(service, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    flags,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_LOW).apply {
                addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        service.getText(R.string.stop),
                        PendingIntent.getBroadcast(
                            service,
                            0,
                            Intent(Action.SERVICE_CLOSE).setPackage(service.packageName),
                            flags,
                        ),
                    ).build(),
                )
            }
    }

    fun show(profileName: String, @StringRes contentTextId: Int) {
        released = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // re-creating with the same id updates the stored channel name on
            // language switches
            InterstellarApplication.notification.createNotificationChannel(
                NotificationChannel(
                    notificationChannel,
                    service.getString(R.string.channel_service),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        // On API 34+ the type must be passed explicitly (the 2-arg overload
        // leaves the service type-less — Android 16 then tears the FGS down
        // shortly after start, silently stopping the core)
        val fgsType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching {
                    service.packageManager.getServiceInfo(
                        android.content.ComponentName(service, service.javaClass),
                        0,
                    ).foregroundServiceType
                }.getOrDefault(0)
            } else {
                0
            }
        ServiceCompat.startForeground(
            service,
            notificationId,
            notificationBuilder
                .setContentTitle(profileName.takeIf { it.isNotBlank() } ?: service.getString(R.string.app_tagline))
                .setContentText(service.getString(contentTextId)).build(),
            fgsType,
        )
    }

    suspend fun start() {
        if (Settings.dynamicNotification && checkPermission()) {
            commandClient.connect()
            withContext(Dispatchers.Main) {
                registerReceiver()
            }
        }
    }

    private fun registerReceiver() {
        service.registerReceiver(
            this,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        receiverRegistered = true
    }

    override fun updateStatus(status: StatusMessage) {
        updateTraffic(status.uplink, status.downlink)
    }

    /** Engine-agnostic traffic line (mihomo poller calls this directly). */
    fun updateTraffic(upPerSecond: Long, downPerSecond: Long) {
        if (released || !Settings.dynamicNotification || !checkPermission()) return
        val content =
            Libbox.formatBytes(upPerSecond) + "/s ↑\t" + Libbox.formatBytes(downPerSecond) + "/s ↓"
        InterstellarApplication.notificationManager.notify(
            notificationId,
            notificationBuilder.setContentText(content).build(),
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> commandClient.connect()

            Intent.ACTION_SCREEN_OFF -> commandClient.disconnect()
        }
    }

    fun close() {
        released = true
        commandClient.disconnect()
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // a traffic update may have slipped in just before stopForeground ran;
        // cancel it explicitly — it would not be removed by service death
        InterstellarApplication.notificationManager.cancel(notificationId)
        if (receiverRegistered) {
            service.unregisterReceiver(this)
            receiverRegistered = false
        }
    }
}
