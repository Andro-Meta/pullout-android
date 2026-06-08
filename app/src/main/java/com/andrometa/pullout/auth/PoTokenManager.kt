package com.andrometa.pullout.auth

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.ServerSocket

/**
 * Owns the YouTube po_token lifecycle: a NanoHTTPD server on a reserved loopback
 * port plus a hidden WebView that harvests the token. cobalt pulls the token via
 * YOUTUBE_SESSION_SERVER (= sessionServerUrl).
 *
 * Ordering contract (see CobaltServerService / NodeServerManager):
 *   1. reservePort()  — synchronous; sessionServerUrl becomes valid.
 *   2. NodeServerManager.startServer() writes .env using sessionServerUrl.
 *   3. start(context) — brings up the server socket + WebView.
 */
object PoTokenManager {
    private const val TAG = "PoTokenManager"
    private const val BASE_PORT = 9001
    private const val REFRESH_INTERVAL_MS = 30L * 60L * 1000L   // 30 min
    private const val TOKEN_WAIT_MS = 60_000L                   // wait for first token
    private const val WAIT_POLL_MS = 1_000L
    private const val MAX_BACKOFF_RETRIES = 3

    @Volatile private var port: Int = BASE_PORT
    @Volatile private var server: PoTokenServer? = null
    @Volatile private var webView: PoTokenWebView? = null
    @Volatile private var portReserved = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null

    val sessionServerUrl: String get() = "http://127.0.0.1:$port/"

    fun isReady(): Boolean = server?.token != null

    /**
     * Reserve a free port (starting at BASE_PORT) and create the HTTP server
     * bound to it. Safe to call multiple times; only the first reserves.
     */
    @Synchronized
    fun reservePort() {
        if (portReserved) return
        port = findFreePort(BASE_PORT)
        server = PoTokenServer(port)
        portReserved = true
        Log.i(TAG, "reserved po_token port $port (server $sessionServerUrl)")
    }

    @Volatile private var onTokenCb: ((String, String) -> Unit)? = null

    /**
     * Start ONLY the loopback HTTP server (called from the service). The WebView
     * that actually harvests the token is attached later from an Activity via
     * attachWebHost(), because YouTube's player needs a real rendering surface.
     */
    fun startServer(@Suppress("UNUSED_PARAMETER") context: Context) {
        reservePort()
        try {
            server?.let { if (!it.isAlive) it.start(NanoTimeoutMs, false) }
            Log.i(TAG, "PoTokenServer listening on 127.0.0.1:$port")
        } catch (e: Exception) {
            Log.e(TAG, "failed starting PoTokenServer", e)
        }
    }

    /**
     * Attach the capture WebView to a live Activity's view tree so YouTube's
     * player initializes and emits /youtubei/v1/player. Idempotent: if a WebView
     * is already attached and we already have a token, this is a no-op.
     */
    fun attachWebHost(activity: android.app.Activity) {
        val host = activity.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        if (webView != null && server?.token != null) return
        // Recreate the WebView against the activity context for a valid surface.
        webView?.destroy()
        val wv = PoTokenWebView(activity)
        webView = wv
        wv.start(host) { potoken, visitor ->
            server?.token = PoTokenServer.TokenInfo(potoken, visitor, System.currentTimeMillis())
            Log.i(TAG, "token stored (len=${potoken.length})")
        }
        scheduleRefreshLoop()
    }

    /** Detach the WebView (called from Activity.onDestroy). Server keeps running. */
    fun detachWebHost() {
        refreshJob?.cancel()
        refreshJob = null
        webView?.destroy()
        webView = null
    }

    fun refresh() {
        Log.i(TAG, "manual refresh()")
        webView?.reload()
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
        webView?.destroy()
        webView = null
        server?.stop()
        server = null
        portReserved = false
    }

    private fun scheduleRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            // Initial token-wait with backoff retries.
            awaitTokenWithBackoff()
            while (true) {
                delay(REFRESH_INTERVAL_MS)
                Log.i(TAG, "scheduled refresh tick")
                webView?.reload()
                awaitTokenWithBackoff()
            }
        }
    }

    /** Wait up to TOKEN_WAIT_MS for a token; on failure reload + retry w/ backoff. */
    private suspend fun awaitTokenWithBackoff() {
        var retry = 0
        while (retry <= MAX_BACKOFF_RETRIES) {
            val deadline = System.currentTimeMillis() + TOKEN_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (server?.token != null) return
                delay(WAIT_POLL_MS)
            }
            if (server?.token != null) return
            retry++
            if (retry > MAX_BACKOFF_RETRIES) break
            val backoff = TOKEN_WAIT_MS * retry
            Log.w(TAG, "no token after wait; retry $retry/$MAX_BACKOFF_RETRIES in ${backoff}ms")
            webView?.reload()
            delay(backoff)
        }
        Log.w(TAG, "token still pending after $MAX_BACKOFF_RETRIES retries (non-fatal)")
    }

    private fun findFreePort(start: Int): Int {
        for (p in start until start + 50) {
            try {
                ServerSocket().use { sock ->
                    sock.reuseAddress = true
                    sock.bind(java.net.InetSocketAddress("127.0.0.1", p))
                }
                return p
            } catch (_: Exception) { /* in use, try next */ }
        }
        Log.w(TAG, "no free port in range; falling back to $start")
        return start
    }

    // NanoHTTPD.start(timeout, daemon). 5s socket read timeout, foreground thread.
    private const val NanoTimeoutMs = 5_000
}
