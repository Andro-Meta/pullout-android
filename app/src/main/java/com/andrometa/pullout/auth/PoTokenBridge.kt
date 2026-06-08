package com.andrometa.pullout.auth

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * JS-callable bridge exposed to the hidden YouTube WebView as `PoTokenBridge`.
 * potoken_capture.js calls window.PoTokenBridge.onToken(poToken, visitorData).
 *
 * NOTE: @JavascriptInterface methods run on a private WebView JS thread, NOT the
 * main thread. The listener implementation must marshal to whatever thread it
 * needs (PoTokenManager stores into a @Volatile field, which is thread-safe).
 */
class PoTokenBridge(
    private val onValidToken: (potoken: String, visitorData: String) -> Unit
) {
    @JavascriptInterface
    fun onToken(potoken: String?, visitorData: String?) {
        val tok = potoken?.trim().orEmpty()
        val vis = visitorData?.trim().orEmpty()
        if (tok.isBlank() || vis.isBlank()) {
            Log.w(TAG, "rejected token: blank field (tokLen=${tok.length}, visLen=${vis.length})")
            return
        }
        if (tok.length < MIN_POTOKEN_LEN) {
            Log.w(TAG, "rejected token: too short (len=${tok.length}, need >= $MIN_POTOKEN_LEN)")
            return
        }
        Log.i(TAG, "accepted poToken (len=${tok.length}, visitorLen=${vis.length})")
        onValidToken(tok, vis)
    }

    companion object {
        private const val TAG = "PoTokenBridge"
        const val MIN_POTOKEN_LEN = 160
    }
}
