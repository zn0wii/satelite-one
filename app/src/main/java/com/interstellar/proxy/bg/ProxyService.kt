package com.interstellar.proxy.bg

import android.app.Service
import android.content.Intent
import com.interstellar.proxy.ktx.wrapAppLocale
import io.nekohasekai.libbox.Notification

class ProxyService :
    Service(),
    PlatformInterfaceWrapper {
    private val service = BoxService(this, this)

    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base?.wrapAppLocale())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = service.onStartCommand()

    override fun onBind(intent: Intent) = service.onBind()

    override fun onDestroy() = service.onDestroy()

    override fun sendNotification(notification: Notification) = service.sendNotification(notification)

    override fun cancelNotification(identifier: String, typeID: Int) = service.cancelNotification(identifier, typeID)
}
