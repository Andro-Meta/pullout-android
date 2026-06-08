package com.andrometa.pullout.auth

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

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

    fun start(onToken: (potoken: String, visitorData: String) -> Unit) {
        runOnMain {
            captureJs = readAsset("js/potoken_capture.js")
            if (captureJs.isBlank()) {
                Log.e(TAG, "potoken_capture.js missing/empty — aborting")
                return@runOnMain
            }
            val wv = WebView(context)
            webView = wv
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
            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    inject(view)
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
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
        // Stable public video ("Me at the zoo"); WEB_EMBEDDED-compatible embed.
        private const val EMBED_URL =
            "https://www.youtube.com/embed/jNQXAC9IVRw?autoplay=1&mute=1&playsinline=1"
    }
}
