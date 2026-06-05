package com.andrometa.pullout

import android.app.Application
import androidx.work.Configuration
import com.andrometa.pullout.server.CobaltServerService
import com.andrometa.pullout.util.NotificationHelper

class PulloutApplication : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper(this).createChannel()
        CobaltServerService.start(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
