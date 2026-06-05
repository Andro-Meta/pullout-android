package com.andrometa.pullout.util

import android.content.Context

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("pullout_settings", Context.MODE_PRIVATE)

    var cobaltInstanceUrl: String
        get() = prefs.getString("cobalt_url", "http://localhost:9000") ?: "http://localhost:9000"
        set(v) { prefs.edit().putString("cobalt_url", v).apply() }

    var audioOnlyMode: Boolean
        get() = prefs.getBoolean("audio_only", false)
        set(v) { prefs.edit().putBoolean("audio_only", v).apply() }

    var defaultQuality: String
        get() = prefs.getString("quality", "1080") ?: "1080"
        set(v) { prefs.edit().putString("quality", v).apply() }

    var clipboardTriggerEnabled: Boolean
        get() = prefs.getBoolean("clipboard_trigger", true)
        set(v) { prefs.edit().putBoolean("clipboard_trigger", v).apply() }

    var firstLaunchDone: Boolean
        get() = prefs.getBoolean("first_launch_done", false)
        set(v) { prefs.edit().putBoolean("first_launch_done", v).apply() }
}
