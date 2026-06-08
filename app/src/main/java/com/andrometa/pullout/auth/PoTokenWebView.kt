package com.andrometa.pullout.auth

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Hidden, off-screen WebView that loads a YouTube embed and harvests the
 * po_token via potoken_capture.js. The WebView is never attached to a visible
 * window; it runs JS off-screen using the supplied (service/application) context.
 *
 * All WebView APIs MUST be touched on the main thread — every public method
 * marshals onto Looper.getMainLooper().
 */
@SuppressLint("SetJavaScriptEnabled")
class PoTokenWebView(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var captureJs: String = ""

    private val mobileUserAgent =
        "Mozilla/5.0 (Linux; Android 14; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    fun start(
        host: android.view.ViewGroup?,
        onToken: (potoken: String, visitorData: String) -> Unit
    ) {
        runOnMain {
            captureJs = readAsset("js/potoken_capture.js")
            if (captureJs.isBlank()) {
                Log.e(TAG, "potoken_capture.js missing/empty — aborting")
                return@runOnMain
            }
            val wv = WebView(context)
            webView = wv
            // CRITICAL: YouTube's player only initializes (and fires /youtubei/v1/player)
            // when the WebView has a real rendering surface. A detached/off-screen WebView
            // never lays out the player. Attach to the host at 1x1 so it renders but is
            // visually imperceptible. cobalt's generator likewise uses a visible browser.
            if (host != null) {
                // Full-size surface so YouTube actually STREAMS (a 1x1 player loads
                // metadata but never starts playback, so the /player request that
                // carries the po_token never fires). Added at index 0 (behind the
                // app's opaque #060608 UI), so it renders + plays but stays invisible.
                val lp = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                wv.layoutParams = lp
                // DIAGNOSTIC: add on top, visible, to confirm Chromium's visibility-gated
                // autoplay is what blocks the /player request when occluded.
                host.addView(wv)
                Log.i(TAG, "WebView attached to host (VISIBLE diagnostic) for playback")
            } else {
                Log.w(TAG, "no host ViewGroup — player may not initialize")
            }
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = mobileUserAgent
                cacheMode = WebSettings.LOAD_DEFAULT
                @Suppress("DEPRECATION")
                databaseEnabled = true
            }
            wv.addJavascriptInterface(PoTokenBridge(onToken), "PoTokenBridge")

            // CRITICAL: install the fetch/XHR wrapper BEFORE any YouTube script runs.
            // Without true document-start injection the page can fire /youtubei/v1/player
            // before onPageStarted, and we miss the po_token entirely. This is exactly
            // what cobalt's yt-session-generator gets "for free" by driving Chromium via
            // CDP. addDocumentStartJavaScript is the WebView-native equivalent.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                runCatching {
                    WebViewCompat.addDocumentStartJavaScript(wv, captureJs, setOf("*"))
                    Log.i(TAG, "document-start injection active")
                }.onFailure { Log.w(TAG, "addDocumentStartJavaScript failed", it) }
            } else {
                Log.w(TAG, "DOCUMENT_START_SCRIPT unsupported; falling back to onPageStarted")
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // Fallback path for WebViews lacking DOCUMENT_START_SCRIPT.
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        inject(view)
                    }
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Re-assert the playback nudge after load (the wrapper is already in
                    // place via document-start, so this only kicks playback if needed).
                    inject(view)
                }
            }
            Log.i(TAG, "loading $EMBED_URL")
            wv.loadUrl(EMBED_URL)
        }
    }

    fun reload() {
        runOnMain {
            Log.i(TAG, "reloading embed")
            webView?.loadUrl(EMBED_URL) ?: Log.w(TAG, "reload called before start()")
        }
    }

    fun destroy() {
        runOnMain {
            webView?.apply {
                stopLoading()
                removeJavascriptInterface("PoTokenBridge")
                (parent as? android.view.ViewGroup)?.removeView(this)
                destroy()
            }
            webView = null
        }
    }

    private fun inject(view: WebView?) {
        if (captureJs.isBlank()) return
        view?.evaluateJavascript(captureJs, null)
    }

    private fun readAsset(path: String): String =
        runCatching {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.e(TAG, "failed reading asset $path", it)
            ""
        }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    companion object {
        private const val TAG = "PoTokenWebView"
        // The modern /embed/ player no longer makes a client-side /youtubei/v1/player
        // POST (the response is server-rendered), so we can't intercept the po_token
        // there. The full watch page DOES call /player during playback. We load it
        // muted; potoken_capture.js nudges playback and harvests the request.
        private const val EMBED_URL =
            "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    }
}
