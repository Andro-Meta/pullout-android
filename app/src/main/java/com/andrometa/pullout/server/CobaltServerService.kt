package com.andrometa.pullout.server

import android.app.Service
import android.content.Intent
import android.os.IBinder

class CobaltServerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
