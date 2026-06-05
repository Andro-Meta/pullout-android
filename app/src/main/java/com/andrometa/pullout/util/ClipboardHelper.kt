package com.andrometa.pullout.util

import android.content.ClipboardManager
import android.content.Context

object ClipboardHelper {
    fun getSupportedUrl(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        return UrlMatcher.extractUrl(text)
    }
}
