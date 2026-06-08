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
    private var bgBundleJs: String = ""
    private var generateJs: String = ""

    private val mobileUserAgent =
        "Mozilla/5.0 (Linux; Android 14; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    fun start(
        host: android.view.ViewGroup?,
        onToken: (potoken: String, visitorData: String) -> Unit
    ) {
        runOnMain {
            // bgConfig bundle (exposes window.BG) + our generator. The bundle is
            // injected via evaluateJavascript so YouTube's CSP can't block it (CSP
            // governs page-loaded resources, not embedder-injected scripts).
            bgBundleJs = readAsset("js/bgutils-bundle.js")
            generateJs = readAsset("js/potoken_generate.js")
            if (bgBundleJs.isBlank() || generateJs.isBlank()) {
                Log.e(TAG, "bgutils bundle/generator asset missing — aborting")
                return@runOnMain
            }
            val wv = WebView(context)
            webView = wv
            // bgutils-js needs NO video playback — only the page context (ytcfg +
            // same-origin fetch to youtube.com's BotGuard endpoints). So the WebView
            // can be genuinely hidden at 1x1; no visible surface is required.
            if (host != null) {
                wv.layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                wv.alpha = 0f
                host.addView(wv)
                Log.i(TAG, "WebView attached to host (hidden 1x1)")
            }
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = mobileUserAgent
                cacheMode = WebSettings.LOAD_DEFAULT
                @Suppress("DEPRECATION")
                databaseEnabled = true
            }
            wv.addJavascriptInterface(PoTokenBridge(onToken), "PoTokenBridge")

            // Install a pass-through Trusted Types "default" policy BEFORE YouTube's
            // scripts run, so bgutils-js can load the BotGuard VM via new Function().
            // Must be document-start: YouTube enforces require-trusted-types-for 'script'.
            val ttShim = readAsset("js/trustedtypes_shim.js")
            if (ttShim.isNotBlank() &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            ) {
                runCatching {
                    WebViewCompat.addDocumentStartJavaScript(wv, ttShim, setOf("*"))
                    Log.i(TAG, "trusted-types shim armed at document-start")
                }.onFailure { Log.w(TAG, "addDocumentStartJavaScript failed", it) }
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Inject the BotGuard bundle first (defines window.BG), then the
                    // generator which reads visitor_data from ytcfg and mints the token.
                    view?.evaluateJavascript(bgBundleJs) {
                        view.evaluateJavascript(generateJs, null)
                    }
                    Log.i(TAG, "injected bgutils bundle + generator")
                }
            }
            Log.i(TAG, "loading $YT_URL")
            wv.loadUrl(YT_URL)
        }
    }

    fun reload() {
        runOnMain {
            Log.i(TAG, "reloading youtube for fresh token")
            webView?.loadUrl(YT_URL) ?: Log.w(TAG, "reload called before start()")
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
        // Load the real YouTube homepage: it provides ytcfg (visitor_data) and makes
        // youtube.com same-origin, so bgutils-js can call the BotGuard Create/GenerateIT
        // endpoints via useYouTubeAPI without a CORS block. No video is played.
        private const val YT_URL = "https://www.youtube.com/"
    }
}
