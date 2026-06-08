package com.andrometa.pullout.server

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.andrometa.pullout.util.NotificationHelper

class CobaltServerService : Service() {

    private lateinit var notificationHelper: NotificationHelper

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MUST call startForeground on main thread BEFORE any coroutine or background work
        val notification = notificationHelper.buildServerNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.FOREGROUND_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.FOREGROUND_ID, notification)
        }

        // 1) Reserve the po_token loopback port so YOUTUBE_SESSION_SERVER is known
        //    BEFORE NodeServerManager writes the .env file.
        com.andrometa.pullout.auth.PoTokenManager.reservePort()
        // 2) Start cobalt (writes .env using the reserved port, then boots node).
        NodeServerManager.startServer(this)
        // 3) Bring up the po_token HTTP server (loopback). The capture WebView is
        //    attached later from MainActivity (it needs a real Activity surface to
        //    initialize YouTube's player).
        com.andrometa.pullout.auth.PoTokenManager.startServer(applicationContext)
        return START_STICKY  // Restart service if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, CobaltServerService::class.java))
        }
    }
}
