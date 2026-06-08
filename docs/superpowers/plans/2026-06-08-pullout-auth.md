# PULLOUT Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add cookie login + on-device YouTube po_token generation so YouTube (full quality), Instagram, Twitter/X, and Reddit work — a faithful port of cobalt's yt-session-generator.

**Architecture:** A hidden WebView loads a YouTube embed; injected JS wraps fetch/XHR to harvest the /youtubei/v1/player request body (visitorData + poToken); NanoHTTPD serves these at POST /get_pot on 127.0.0.1:9001; cobalt pulls them via YOUTUBE_SESSION_SERVER. An in-app LoginActivity WebView captures service cookies into cobalt's cookies.json (fed via COOKIE_PATH). Env vars injected via a .env file in the nodejs-project dir.

**Tech Stack:** Kotlin · NanoHTTPD 2.3.1 · Android WebView (Chromium) · nodejs-mobile · cobalt API · minSdk 26 · targetSdk 35

---

## Context for workers

- **Package:** `com.andrometa.pullout` · **Project root:** `D:\Projects\PULLOUT`
- **Build:** `.\gradlew assembleDebug` (Windows PowerShell). Unit tests: `.\gradlew testDebugUnitTest`.
- **Device:** `R5CT31LB06P` (S22 Ultra). Install with `.\gradlew installDebug` or `adb -s R5CT31LB06P install -r <apk>`.
- **Git:** branch off before committing if on default; remote `origin` = `https://github.com/Andro-Meta/pullout-android.git`. `core.autocrlf=true` — write Kotlin/JS/XML with LF; git normalizes.
- **Existing patterns to mirror (verified):**
  - Bottom sheets use `BottomSheetDialogFragment` with `private var _b: <Binding>? = null; private val b get() = _b!!`, inflate in `onCreateView`, null `_b` in `onDestroyView`. See `ui/DownloadQueueSheet.kt`, `ui/SettingsSheet.kt`.
  - `NodeServerManager` is an `object` with `managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`; port 9000; `prepareNodeProject(context): String` copies assets to `filesDir/nodejs-project`.
  - `CobaltServerService.onStartCommand` calls `startForeground` first, then `NodeServerManager.startServer(this)`, returns `START_STICKY`.
  - Colors: `@color/background #060608`, `@color/surface #0E0E16`, `@color/neon_cyan #00E5FF`, `@color/neon_green #39FF14`, `@color/neon_red #FF2244`, `@color/text_primary #D8E4FF`, `@color/text_secondary #3A4870`, `@color/border #0A1030`. Fonts: `@font/space_mono_regular`, `@font/space_mono_bold`. Input bg drawables: `@drawable/bg_input_normal`, `@drawable/bg_input_focused`.
  - Tests live in `app/src/test/java/com/andrometa/pullout/` and use JUnit 4 (`org.junit.Test`, `org.junit.Assert.*`).
- **cobalt env contract (verified against `src/core/env.js`):** `loadEnvs` reads `process.env.COOKIE_PATH` → `cookiePath`, `process.env.YOUTUBE_SESSION_SERVER` → `ytSessionServer`, `process.env.YOUTUBE_SESSION_INNERTUBE_CLIENT` → `ytSessionInnertubeClient`. **`main.js` does NOT load dotenv** — it only sets `API_URL/API_PORT/API_LISTEN_ADDRESS`. Task 8 therefore (a) writes a `.env` file AND (b) patches `main.js` to read that `.env` into `process.env` before importing cobalt, so the values survive the JNI boundary.
- **cobalt session contract (verified against `src/processing/helpers/youtube-session.js`):** POSTs `{ytSessionServer}/get_pot`, expects JSON `{potoken, visitor_data, updated}` (it also accepts `poToken`/`contentBinding` aliases); warns if `potoken.length < 160`. On success logs `poToken & visitor_data loaded successfully!`.

---

### Task 1: NanoHTTPD dependency + PoTokenServer

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/andrometa/pullout/auth/PoTokenServer.kt`
- Test: `app/src/test/java/com/andrometa/pullout/PoTokenServerTest.kt`

- [ ] Step 1: Add the NanoHTTPD dependency. In `app/build.gradle.kts`, inside the `dependencies { ... }` block, add the line directly after the okhttp line.

Replace:
```kotlin
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
```
with:
```kotlin
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
```

- [ ] Step 2: Create `app/src/main/java/com/andrometa/pullout/auth/PoTokenServer.kt` with COMPLETE contents:

```kotlin
package com.andrometa.pullout.auth

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Minimal loopback HTTP server that serves the captured YouTube po_token to
 * cobalt's youtube-session.js. cobalt POSTs {ytSessionServer}/get_pot and
 * expects {potoken, visitor_data, updated}. Returns 503 until a token exists.
 *
 * Bound to 127.0.0.1 only — never a public interface.
 */
class PoTokenServer(port: Int) : NanoHTTPD("127.0.0.1", port) {

    data class TokenInfo(val potoken: String, val visitorData: String, val updated: Long)

    @Volatile
    var token: TokenInfo? = null

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        if (uri != "/get_pot") {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}"""
            )
        }

        val current = token
        return if (current == null) {
            Log.w(TAG, "/get_pot polled before first token ready -> 503")
            newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                """{"error":"no token yet"}"""
            )
        } else {
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                serializeToken(current)
            )
        }
    }

    companion object {
        private const val TAG = "PoTokenServer"

        /** Pure JSON serialization — unit-testable without sockets. */
        fun serializeToken(t: TokenInfo): String =
            JSONObject()
                .put("potoken", t.potoken)
                .put("visitor_data", t.visitorData)
                .put("updated", t.updated)
                .toString()
    }
}
```

- [ ] Step 3: Create `app/src/test/java/com/andrometa/pullout/PoTokenServerTest.kt` with COMPLETE contents (tests the pure serialization, not the socket):

```kotlin
package com.andrometa.pullout

import com.andrometa.pullout.auth.PoTokenServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PoTokenServerTest {

    @Test fun serializesAllThreeFields() {
        val t = PoTokenServer.TokenInfo(
            potoken = "X".repeat(200),
            visitorData = "CgtSEST_VISITOR",
            updated = 1717800000000L
        )
        val json = JSONObject(PoTokenServer.serializeToken(t))
        assertEquals("X".repeat(200), json.getString("potoken"))
        assertEquals("CgtSEST_VISITOR", json.getString("visitor_data"))
        assertEquals(1717800000000L, json.getLong("updated"))
    }

    @Test fun usesCobaltExpectedKeys() {
        val t = PoTokenServer.TokenInfo("tok", "vis", 1L)
        val raw = PoTokenServer.serializeToken(t)
        // cobalt's youtube-session.js reads exactly these snake_case keys
        assertTrue(raw.contains("\"potoken\""))
        assertTrue(raw.contains("\"visitor_data\""))
        assertTrue(raw.contains("\"updated\""))
    }
}
```

> Note: `org.json` is available at unit-test time on Android Gradle Plugin 8+ via the bundled stub; if `JSONObject` throws "not mocked", add `testOptions { unitTests.isReturnDefaultValues = false }` is NOT enough — instead these tests already run against the real `org.json` shipped in the Android SDK's `android.jar` mockable jar. If the test fails to find `org.json` at JVM test time, add `testImplementation("org.json:json:20231013")` to `app/build.gradle.kts` and re-run. Apply that fallback only if the build error explicitly says `org.json` cannot be resolved.

- [ ] Step 4: Build and run the unit test.

```powershell
.\gradlew testDebugUnitTest --tests "com.andrometa.pullout.PoTokenServerTest"
```
Expected output: `BUILD SUCCESSFUL`, with `PoTokenServerTest` showing 2 passing tests. If you hit the `org.json` resolution error, apply the fallback dependency noted in Step 3 and re-run.

- [ ] Step 5: Confirm the dependency resolves in a full compile.

```powershell
.\gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`. (NanoHTTPD `fi.iki.elonen.NanoHTTPD` now on the classpath.)

- [ ] Step 6: Commit.

```bash
git add -A && git commit -m "feat(auth): add NanoHTTPD dep + PoTokenServer /get_pot endpoint

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: potoken_capture.js (fetch/XHR wrapper)

**Files:**
- Create: `app/src/main/assets/js/potoken_capture.js`

- [ ] Step 1: Create `app/src/main/assets/js/potoken_capture.js` with COMPLETE contents. This is an IIFE injected into the hidden YouTube embed WebView. It wraps `fetch` and `XMLHttpRequest`, harvests the `/youtubei/v1/player` request body, extracts `context.client.visitorData` and `serviceIntegrityDimensions.poToken`, and forwards them to the native `PoTokenBridge`. It also nudges playback so YouTube generates the token.

```javascript
(function () {
    "use strict";

    // Guard against double-install (onPageStarted + onPageFinished both inject).
    if (window.__PULLOUT_POTOKEN_INSTALLED__) {
        return;
    }
    window.__PULLOUT_POTOKEN_INSTALLED__ = true;

    var PLAYER_PATH = "/youtubei/v1/player";

    function log(msg) {
        try { console.log("[pullout-potoken] " + msg); } catch (e) {}
    }

    function deliver(body) {
        if (!body) return false;
        var json;
        try {
            json = (typeof body === "string") ? JSON.parse(body) : body;
        } catch (e) {
            return false;
        }
        try {
            var visitorData =
                json &&
                json.context &&
                json.context.client &&
                json.context.client.visitorData;
            var poToken =
                json &&
                json.serviceIntegrityDimensions &&
                json.serviceIntegrityDimensions.poToken;
            if (visitorData && poToken) {
                if (window.PoTokenBridge && window.PoTokenBridge.onToken) {
                    window.PoTokenBridge.onToken(poToken, visitorData);
                    log("delivered poToken (len=" + poToken.length + ")");
                    return true;
                }
            }
        } catch (e) {
            log("deliver error: " + e);
        }
        return false;
    }

    // ── Wrap fetch ──────────────────────────────────────────────────────────
    var originalFetch = window.fetch;
    if (originalFetch) {
        window.fetch = function (resource, options) {
            try {
                var url =
                    (typeof resource === "string")
                        ? resource
                        : (resource && resource.url) || "";
                if (url.indexOf(PLAYER_PATH) !== -1 && options && options.body) {
                    deliver(options.body);
                }
            } catch (e) {
                log("fetch wrap error: " + e);
            }
            return originalFetch.apply(this, arguments);
        };
    }

    // ── Wrap XMLHttpRequest ────────────────────────────────────────────────
    var originalOpen = XMLHttpRequest.prototype.open;
    var originalSend = XMLHttpRequest.prototype.send;

    XMLHttpRequest.prototype.open = function (method, url) {
        try {
            this.__pullout_url__ = url;
        } catch (e) {}
        return originalOpen.apply(this, arguments);
    };

    XMLHttpRequest.prototype.send = function (body) {
        try {
            var url = this.__pullout_url__ || "";
            if (url.indexOf(PLAYER_PATH) !== -1 && body) {
                deliver(body);
            }
        } catch (e) {
            log("xhr wrap error: " + e);
        }
        return originalSend.apply(this, arguments);
    };

    // ── Nudge playback so YouTube emits the /player request ────────────────
    var attempts = 0;
    var MAX_ATTEMPTS = 30; // ~15s at 500ms

    function tryPlay() {
        attempts++;
        try {
            var moviePlayer = document.querySelector("#movie_player");
            if (moviePlayer && typeof moviePlayer.click === "function") {
                moviePlayer.click();
            }
        } catch (e) {}
        try {
            var videos = document.getElementsByTagName("video");
            for (var i = 0; i < videos.length; i++) {
                var p = videos[i].play();
                if (p && typeof p.catch === "function") {
                    p.catch(function () {});
                }
            }
        } catch (e) {}
        if (attempts >= MAX_ATTEMPTS) {
            clearInterval(playTimer);
        }
    }

    var playTimer = setInterval(tryPlay, 500);

    function onReady() {
        tryPlay();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", onReady);
    } else {
        onReady();
    }

    log("installed");
})();
```

- [ ] Step 2: Build to confirm the new asset compiles into the APK. `androidResources.noCompress` already includes `js`, so it is stored uncompressed.

```powershell
.\gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Step 3: Commit.

```bash
git add -A && git commit -m "feat(auth): add potoken_capture.js fetch/XHR wrapper + play nudge

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: PoTokenBridge + PoTokenWebView

**Files:**
- Create: `app/src/main/java/com/andrometa/pullout/auth/PoTokenBridge.kt`
- Create: `app/src/main/java/com/andrometa/pullout/auth/PoTokenWebView.kt`

- [ ] Step 1: Create `app/src/main/java/com/andrometa/pullout/auth/PoTokenBridge.kt` with COMPLETE contents. Validates the token (length ≥ 160, both non-blank) before forwarding to the listener.

```kotlin
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
```

- [ ] Step 2: Create `app/src/main/java/com/andrometa/pullout/auth/PoTokenWebView.kt` with COMPLETE contents. Wraps an off-screen `WebView` constructed against the service context, enables JS + DOM storage, sets a mobile UA, injects `potoken_capture.js` at both `onPageStarted` and `onPageFinished`, loads the YouTube embed, and wires `PoTokenBridge`. All WebView construction and `loadUrl`/`reload`/`destroy` are marshalled onto the main thread.

```kotlin
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
```

- [ ] Step 3: Build.

```powershell
.\gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Step 4: Commit.

```bash
git add -A && git commit -m "feat(auth): add PoTokenBridge + off-screen PoTokenWebView

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: PoTokenManager

**Files:**
- Create: `app/src/main/java/com/andrometa/pullout/auth/PoTokenManager.kt`

- [ ] Step 1: Create `app/src/main/java/com/andrometa/pullout/auth/PoTokenManager.kt` with COMPLETE contents. Singleton coordinating `PoTokenServer` + `PoTokenWebView`. `reservePort()` synchronously binds a free port starting at 9001 (so `sessionServerUrl` is known before `.env` is written). `start()` brings up the WebView and stores captured tokens. Auto-refresh every 30 min; if no token within 60s of a (re)load, retry with backoff.

```kotlin
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

    fun start(context: Context) {
        reservePort()
        try {
            server?.let {
                if (!it.isAlive) it.start(NanoTimeoutMs, false)
            }
            Log.i(TAG, "PoTokenServer listening on 127.0.0.1:$port")
        } catch (e: Exception) {
            Log.e(TAG, "failed starting PoTokenServer", e)
        }

        if (webView == null) {
            webView = PoTokenWebView(context)
        }
        webView?.start { potoken, visitor ->
            server?.token = PoTokenServer.TokenInfo(potoken, visitor, System.currentTimeMillis())
            Log.i(TAG, "token stored (len=${potoken.length})")
        }
        scheduleRefreshLoop()
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
```

- [ ] Step 2: Build.

```powershell
.\gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Step 3: Commit.

```bash
git add -A && git commit -m "feat(auth): add PoTokenManager (port reservation, refresh loop, backoff)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: CookieStore + AccountStatus

**Files:**
- Create: `app/src/main/java/com/andrometa/pullout/auth/AccountStatus.kt`
- Create: `app/src/main/java/com/andrometa/pullout/auth/CookieStore.kt`
- Test: `app/src/test/java/com/andrometa/pullout/CookieStoreTest.kt`

- [ ] Step 1: Create `app/src/main/java/com/andrometa/pullout/auth/AccountStatus.kt` with COMPLETE contents (the per-service status model + the static service registry).

```kotlin
package com.andrometa.pullout.auth

/** Per-service login status surfaced to the UI. */
data class AccountStatus(
    val service: String,
    val loggedIn: Boolean,
    val lastUpdated: Long
)

/**
 * Static configuration for each auth-gated service: the cobalt cookies.json key,
 * the login URL, which CookieManager domains to read, which cookie keys prove a
 * login, and which keys cobalt actually wants persisted.
 */
data class ServiceConfig(
    val id: String,
    val displayName: String,
    val loginUrl: String,
    val cookieDomains: List<String>,
    val requiredCookieKeys: List<String>,
    val relevantCookieKeys: List<String>
)

object AuthServices {
    val ALL: List<ServiceConfig> = listOf(
        ServiceConfig(
            id = "youtube",
            displayName = "youtube",
            loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube",
            cookieDomains = listOf("https://www.google.com", "https://www.youtube.com"),
            // SID + SAPISID, OR the modern __Secure-1PSID, prove the login.
            requiredCookieKeys = listOf("SID", "SAPISID"),
            relevantCookieKeys = listOf(
                "SID", "HSID", "SSID", "APISID", "SAPISID",
                "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO", "VISITOR_INFO1_LIVE"
            )
        ),
        ServiceConfig(
            id = "instagram",
            displayName = "instagram",
            loginUrl = "https://www.instagram.com/accounts/login/",
            cookieDomains = listOf("https://www.instagram.com"),
            requiredCookieKeys = listOf("sessionid", "ds_user_id"),
            relevantCookieKeys = listOf("mid", "ig_did", "csrftoken", "ds_user_id", "sessionid")
        ),
        ServiceConfig(
            id = "twitter",
            displayName = "twitter / x",
            loginUrl = "https://x.com/login",
            cookieDomains = listOf("https://x.com"),
            requiredCookieKeys = listOf("auth_token", "ct0"),
            relevantCookieKeys = listOf("auth_token", "ct0")
        ),
        ServiceConfig(
            id = "reddit",
            displayName = "reddit (basic)",
            loginUrl = "https://www.reddit.com/login",
            cookieDomains = listOf("https://www.reddit.com"),
            requiredCookieKeys = listOf("reddit_session"),
            relevantCookieKeys = listOf("reddit_session")
        )
    )

    fun byId(id: String): ServiceConfig? = ALL.firstOrNull { it.id == id }
}
```

> YouTube `requiredCookieKeys` lists `SID`+`SAPISID`; `CookieStore.hasRequired` additionally treats `__Secure-1PSID` as a valid substitute for the pair (see implementation). This matches the spec ("SID AND SAPISID (or __Secure-1PSID)").

- [ ] Step 2: Create `app/src/main/java/com/andrometa/pullout/auth/CookieStore.kt` with COMPLETE contents. Reads/writes `filesDir/nodejs-project/cookies.json` (cobalt's `COOKIE_PATH`). Pure helpers (`filterCookies`, `hasRequired`, `toCobaltJson`) are extracted for unit testing.

```kotlin
package com.andrometa.pullout.auth

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads service cookies out of the WebView CookieManager and persists them into
 * cobalt's cookies.json shape: { serviceId: ["k=v; k2=v2"] }. cobalt reads this
 * file at startup via COOKIE_PATH.
 */
object CookieStore {
    private const val TAG = "CookieStore"

    fun cookiesFile(context: Context): File {
        val dir = File(context.filesDir, "nodejs-project")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "cookies.json")
    }

    /**
     * Read CookieManager for this service's domains, filter to relevant keys,
     * verify required keys are present, and return "k=v; k=v" — or null if the
     * required cookies are missing (i.e. not actually logged in yet).
     */
    fun buildCookieStringFromManager(serviceId: String): String? {
        val cfg = AuthServices.byId(serviceId) ?: return null
        val cm = CookieManager.getInstance()
        cm.flush()

        val merged = LinkedHashMap<String, String>()
        for (domain in cfg.cookieDomains) {
            val raw = cm.getCookie(domain) ?: continue
            merged.putAll(filterCookies(raw, cfg.relevantCookieKeys))
        }
        if (merged.isEmpty()) return null
        if (!hasRequired(merged, cfg.requiredCookieKeys)) {
            Log.i(TAG, "$serviceId: required cookies not present yet")
            return null
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** Merge/replace one service into cookies.json. */
    fun saveService(context: Context, serviceId: String, cookieString: String) {
        val file = cookiesFile(context)
        val root = readRoot(file)
        root.put(serviceId, JSONArray().put(cookieString))
        file.writeText(root.toString())
        Log.i(TAG, "saved cookies for $serviceId (${cookieString.length} chars)")
    }

    /** Drop one service from cookies.json. */
    fun removeService(context: Context, serviceId: String) {
        val file = cookiesFile(context)
        val root = readRoot(file)
        root.remove(serviceId)
        file.writeText(root.toString())
        Log.i(TAG, "removed cookies for $serviceId")
    }

    fun loggedInServices(context: Context): Set<String> {
        val root = readRoot(cookiesFile(context))
        val out = HashSet<String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = root.optJSONArray(k)
            if (arr != null && arr.length() > 0 && arr.optString(0).isNotBlank()) out.add(k)
        }
        return out
    }

    private fun readRoot(file: File): JSONObject =
        if (file.exists()) {
            runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        } else JSONObject()

    // ── Pure, unit-testable helpers ─────────────────────────────────────────

    /** Parse a raw "k=v; k2=v2" Cookie header into a map, keeping only `relevant`. */
    fun filterCookies(rawHeader: String, relevant: List<String>): Map<String, String> {
        val relevantSet = relevant.toSet()
        val out = LinkedHashMap<String, String>()
        for (part in rawHeader.split(";")) {
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val key = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (key in relevantSet && value.isNotEmpty()) out[key] = value
        }
        return out
    }

    /**
     * True when the login is proven. Either all `required` keys are present, OR
     * (YouTube special-case) the modern __Secure-1PSID cookie is present.
     */
    fun hasRequired(parsed: Map<String, String>, required: List<String>): Boolean {
        if (parsed.containsKey("__Secure-1PSID")) return true
        return required.all { parsed.containsKey(it) }
    }

    /** Serialize a single service's cookie map into cobalt's { id: ["k=v; ..."] } JSON. */
    fun toCobaltJson(serviceId: String, map: Map<String, String>): String {
        val cookieString = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return JSONObject().put(serviceId, JSONArray().put(cookieString)).toString()
    }
}
```

- [ ] Step 3: Create `app/src/test/java/com/andrometa/pullout/CookieStoreTest.kt` with COMPLETE contents (pure-helper tests only — no Android framework):

```kotlin
package com.andrometa.pullout

import com.andrometa.pullout.auth.CookieStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CookieStoreTest {

    private val ytRelevant = listOf(
        "SID", "HSID", "SSID", "APISID", "SAPISID",
        "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO", "VISITOR_INFO1_LIVE"
    )

    @Test fun filterKeepsOnlyRelevantKeys() {
        val raw = "SID=aaa; HSID=bbb; UNRELATED=zzz; SAPISID=ccc"
        val parsed = CookieStore.filterCookies(raw, ytRelevant)
        assertEquals(3, parsed.size)
        assertEquals("aaa", parsed["SID"])
        assertEquals("bbb", parsed["HSID"])
        assertEquals("ccc", parsed["SAPISID"])
        assertFalse(parsed.containsKey("UNRELATED"))
    }

    @Test fun filterIgnoresMalformedPairs() {
        val raw = "SID=aaa; broken; =novalue; SAPISID="
        val parsed = CookieStore.filterCookies(raw, ytRelevant)
        assertEquals(1, parsed.size)
        assertEquals("aaa", parsed["SID"])
    }

    @Test fun hasRequiredPositive() {
        val parsed = mapOf("SID" to "a", "SAPISID" to "b")
        assertTrue(CookieStore.hasRequired(parsed, listOf("SID", "SAPISID")))
    }

    @Test fun hasRequiredMissingKeyNegative() {
        val parsed = mapOf("SID" to "a")
        assertFalse(CookieStore.hasRequired(parsed, listOf("SID", "SAPISID")))
    }

    @Test fun hasRequiredSecure1PsidSubstitute() {
        // Modern YouTube login uses __Secure-1PSID instead of SID/SAPISID.
        val parsed = mapOf("__Secure-1PSID" to "x")
        assertTrue(CookieStore.hasRequired(parsed, listOf("SID", "SAPISID")))
    }

    @Test fun toCobaltJsonExactShape() {
        val map = linkedMapOf("auth_token" to "tok", "ct0" to "csrf")
        val json = CookieStore.toCobaltJson("twitter", map)
        val root = JSONObject(json)
        val arr = root.getJSONArray("twitter")
        assertEquals(1, arr.length())
        assertEquals("auth_token=tok; ct0=csrf", arr.getString(0))
    }
}
```

- [ ] Step 4: Run the unit tests.

```powershell
.\gradlew testDebugUnitTest --tests "com.andrometa.pullout.CookieStoreTest"
```
Expected: `BUILD SUCCESSFUL`, 6 passing tests.

- [ ] Step 5: Full build.

```powershell
.\gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Step 6: Commit.

```bash
git add -A && git commit -m "feat(auth): add AccountStatus/ServiceConfig registry + CookieStore

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: LoginActivity + layout

**Files:**
- Create: `app/src/main/res/layout/activity_login.xml`
- Create: `app/src/main/java/com/andrometa/pullout/auth/LoginActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] Step 1: Add login strings. In `app/src/main/res/values/strings.xml`, add these lines immediately before the closing `</resources>`:

```xml
    <string name="accounts_title">ACCOUNTS</string>
    <string name="account_login">[ LOG IN ]</string>
    <string name="account_logout">[ LOG OUT ]</string>
    <string name="account_status_in">CONNECTED</string>
    <string name="account_status_out">NOT CONNECTED</string>
    <string name="login_close">[ X ]</string>
    <string name="login_failed">couldn\'t capture login — try again</string>
    <string name="settings_accounts">accounts</string>
```

- [ ] Step 2: Create `app/src/main/res/layout/activity_login.xml` with COMPLETE contents (full-screen WebView, themed top bar with service name + close button).

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@color/background">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:background="@color/surface"
        android:paddingStart="16dp"
        android:paddingEnd="8dp"
        android:paddingTop="10dp"
        android:paddingBottom="10dp">

        <TextView
            android:id="@+id/tvLoginService"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:textColor="@color/neon_cyan"
            android:fontFamily="@font/space_mono_bold"
            android:textSize="14sp"
            android:text="@string/accounts_title"/>

        <Button
            android:id="@+id/btnLoginClose"
            style="@style/Widget.Material3.Button.TextButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/login_close"
            android:fontFamily="@font/space_mono_bold"
            android:textColor="@color/neon_red"/>
    </LinearLayout>

    <WebView
        android:id="@+id/webView"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:background="@color/background"/>
</LinearLayout>
```

- [ ] Step 3: Create `app/src/main/java/com/andrometa/pullout/auth/LoginActivity.kt` with COMPLETE contents.

```kotlin
package com.andrometa.pullout.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.andrometa.pullout.databinding.ActivityLoginBinding
import com.google.android.material.snackbar.Snackbar

/**
 * Full-screen WebView login. Loads a service's login URL; after each page load
 * it checks CookieManager for the service's required cookies and, when present,
 * persists them into cobalt's cookies.json and returns RESULT_OK.
 */
@SuppressLint("SetJavaScriptEnabled")
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var serviceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serviceId = intent.getStringExtra(EXTRA_SERVICE).orEmpty()
        val cfg = AuthServices.byId(serviceId)
        if (cfg == null) {
            Log.e(TAG, "unknown service '$serviceId'")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        binding.tvLoginService.text = cfg.displayName
        binding.btnLoginClose.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tryCapture()
            }
        }
        binding.webView.loadUrl(cfg.loginUrl)
    }

    private fun tryCapture() {
        CookieManager.getInstance().flush()
        val cookieString = CookieStore.buildCookieStringFromManager(serviceId) ?: return
        CookieStore.saveService(applicationContext, serviceId, cookieString)
        Log.i(TAG, "captured login for $serviceId")
        val data = Intent().putExtra(EXTRA_SERVICE, serviceId)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack()
        else {
            setResult(Activity.RESULT_CANCELED)
            super.onBackPressed()
        }
    }

    companion object {
        private const val TAG = "LoginActivity"
        const val EXTRA_SERVICE = "extra_service"
    }
}
```

> The `login_failed` snackbar string is defined for parity with the spec's error-handling table; capture simply stays on the page until required cookies appear, so no explicit failure toast fires in the happy path. (Snackbar import is retained for future use; if the unused-import lint blocks the build, remove the `Snackbar` import line — it is not referenced in this happy-path implementation.)

- [ ] Step 4: Register the activity. In `app/src/main/AndroidManifest.xml`, add this `<activity>` block immediately after the closing `</activity>` of `.MainActivity` (before the `<service>` entries):

```xml
        <activity
            android:name=".auth.LoginActivity"
            android:exported="false"
            android:theme="@style/Theme.Pullout"
            android:configChanges="orientation|screenSize|keyboardHidden"/>
```

- [ ] Step 5: Build.

```powershell
.\gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`. If lint flags the unused `Snackbar` import, delete that import line per the note in Step 3 and rebuild.

- [ ] Step 6: Commit.

```bash
git add -A && git commit -m "feat(auth): add LoginActivity + activity_login layout + manifest entry

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: AccountsSheet + layouts + wire into SettingsSheet/MainActivity

**Files:**
- Create: `app/src/main/res/layout/sheet_accounts.xml`
- Create: `app/src/main/res/layout/item_account.xml`
- Create: `app/src/main/java/com/andrometa/pullout/ui/AccountsSheet.kt`
- Modify: `app/src/main/res/layout/sheet_settings.xml`
- Modify: `app/src/main/java/com/andrometa/pullout/ui/SettingsSheet.kt`
- Modify: `app/src/main/java/com/andrometa/pullout/MainActivity.kt`

- [ ] Step 1: Create `app/src/main/res/layout/sheet_accounts.xml` with COMPLETE contents (title + a RecyclerView of service rows).

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp"
    android:background="@color/surface">

    <View android:layout_width="40dp" android:layout_height="4dp"
        android:layout_gravity="center_horizontal" android:layout_marginBottom="16dp"
        android:background="@color/border"/>

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/accounts_title"
        android:textColor="@color/neon_cyan"
        android:fontFamily="@font/space_mono_bold"
        android:textSize="14sp"
        android:layout_marginBottom="12dp"/>

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recyclerAccounts"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:nestedScrollingEnabled="false"/>

    <View android:layout_width="0dp" android:layout_height="16dp"/>
</LinearLayout>
```

- [ ] Step 2: Create `app/src/main/res/layout/item_account.xml` with COMPLETE contents (service name + status + login/logout button).

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:paddingTop="10dp"
    android:paddingBottom="10dp">

    <View
        android:id="@+id/statusDot"
        android:layout_width="8dp"
        android:layout_height="8dp"
        android:layout_marginEnd="10dp"
        android:background="@color/text_secondary"/>

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical">

        <TextView
            android:id="@+id/tvServiceName"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/text_primary"
            android:fontFamily="@font/space_mono_bold"
            android:textSize="14sp"
            tools:ignore="MissingDefaultResource"
            xmlns:tools="http://schemas.android.com/tools"/>

        <TextView
            android:id="@+id/tvServiceStatus"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/text_secondary"
            android:fontFamily="@font/space_mono_regular"
            android:textSize="10sp"/>
    </LinearLayout>

    <Button
        android:id="@+id/btnAccountAction"
        style="@style/Widget.Material3.Button.OutlinedButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:fontFamily="@font/space_mono_bold"
        android:textColor="@color/neon_cyan"/>
</LinearLayout>
```

- [ ] Step 3: Create `app/src/main/java/com/andrometa/pullout/ui/AccountsSheet.kt` with COMPLETE contents. Lists the four services; login launches `LoginActivity` via `registerForActivityResult`; logout clears CookieManager + cookies.json; both fire `onCookiesChanged`. Adapter is inline for simplicity (matches the lightweight style of existing sheets).

```kotlin
package com.andrometa.pullout.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrometa.pullout.R
import com.andrometa.pullout.auth.AuthServices
import com.andrometa.pullout.auth.CookieStore
import com.andrometa.pullout.auth.LoginActivity
import com.andrometa.pullout.auth.ServiceConfig
import com.andrometa.pullout.databinding.ItemAccountBinding
import com.andrometa.pullout.databinding.SheetAccountsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AccountsSheet : BottomSheetDialogFragment() {
    private var _b: SheetAccountsBinding? = null
    private val b get() = _b!!

    /** Fired after any cookie change (login/logout) so the host can restart cobalt. */
    var onCookiesChanged: (() -> Unit)? = null

    private lateinit var adapter: AccountAdapter

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                onCookiesChanged?.invoke()
            }
            refresh()
        }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        SheetAccountsBinding.inflate(i, c, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = AccountAdapter(
            onLogin = { cfg ->
                loginLauncher.launch(
                    Intent(requireContext(), LoginActivity::class.java)
                        .putExtra(LoginActivity.EXTRA_SERVICE, cfg.id)
                )
            },
            onLogout = { cfg ->
                CookieStore.removeService(requireContext(), cfg.id)
                clearCookiesForDomains(cfg)
                onCookiesChanged?.invoke()
                refresh()
            }
        )
        b.recyclerAccounts.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerAccounts.adapter = adapter
        refresh()
    }

    private fun refresh() {
        val loggedIn = CookieStore.loggedInServices(requireContext())
        adapter.submit(AuthServices.ALL, loggedIn)
    }

    private fun clearCookiesForDomains(cfg: ServiceConfig) {
        val cm = CookieManager.getInstance()
        for (domain in cfg.cookieDomains) {
            val existing = cm.getCookie(domain) ?: continue
            for (pair in existing.split(";")) {
                val key = pair.trim().substringBefore("=").trim()
                if (key.isNotEmpty()) {
                    // Expire each cookie on its domain.
                    cm.setCookie(domain, "$key=; Max-Age=0")
                }
            }
        }
        cm.flush()
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    companion object {
        const val TAG = "AccountsSheet"
        fun newInstance() = AccountsSheet()
    }

    // ── Inline adapter ──────────────────────────────────────────────────────
    private class AccountAdapter(
        val onLogin: (ServiceConfig) -> Unit,
        val onLogout: (ServiceConfig) -> Unit
    ) : RecyclerView.Adapter<AccountAdapter.VH>() {

        private var items: List<ServiceConfig> = emptyList()
        private var loggedIn: Set<String> = emptySet()

        fun submit(list: List<ServiceConfig>, logged: Set<String>) {
            items = list
            loggedIn = logged
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemAccountBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(binding)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val cfg = items[position]
            val isIn = loggedIn.contains(cfg.id)
            val ctx = holder.itemView.context
            holder.b.tvServiceName.text = cfg.displayName
            holder.b.tvServiceStatus.text =
                ctx.getString(if (isIn) R.string.account_status_in else R.string.account_status_out)
            holder.b.statusDot.setBackgroundColor(
                ctx.getColor(if (isIn) R.color.neon_green else R.color.text_secondary)
            )
            holder.b.btnAccountAction.text =
                ctx.getString(if (isIn) R.string.account_logout else R.string.account_login)
            holder.b.btnAccountAction.setTextColor(
                ctx.getColor(if (isIn) R.color.neon_red else R.color.neon_cyan)
            )
            holder.b.btnAccountAction.setOnClickListener {
                if (isIn) onLogout(cfg) else onLogin(cfg)
            }
        }

        class VH(val b: ItemAccountBinding) : RecyclerView.ViewHolder(b.root)
    }
}
```

- [ ] Step 4: Add an "accounts" button to the settings sheet. In `app/src/main/res/layout/sheet_settings.xml`, add this button immediately after the `btnBattery` `<Button>` block (before `btnClearHistory`):

```xml
        <Button android:id="@+id/btnAccounts"
            style="@style/Widget.Material3.Button.OutlinedButton"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="@string/settings_accounts"
            android:fontFamily="@font/space_mono_bold"
            android:textColor="@color/neon_cyan"/>
```

- [ ] Step 5: Wire the button + a callback in `SettingsSheet.kt`. Add a public callback property and a click handler.

In `app/src/main/java/com/andrometa/pullout/ui/SettingsSheet.kt`, replace:
```kotlin
    var onCobaltUrlChanged: ((String) -> Unit)? = null
```
with:
```kotlin
    var onCobaltUrlChanged: ((String) -> Unit)? = null
    var onCookiesChanged: (() -> Unit)? = null
```

Then, inside `onViewCreated`, add this handler immediately after the `_b?.btnDocs?.setOnClickListener { ... }` block (just before the closing brace of `onViewCreated`):
```kotlin
        _b?.btnAccounts?.setOnClickListener {
            AccountsSheet.newInstance().also { sheet ->
                sheet.onCookiesChanged = { onCookiesChanged?.invoke() }
                sheet.show(parentFragmentManager, AccountsSheet.TAG)
            }
        }
```

- [ ] Step 6: Wire `onCookiesChanged` through `MainActivity` to restart cobalt. In `app/src/main/java/com/andrometa/pullout/MainActivity.kt`, replace the `setupSettings()` function:

```kotlin
    private fun setupSettings() {
        binding.tvSettings.setOnClickListener {
            SettingsSheet.newInstance().also { sheet ->
                sheet.onCobaltUrlChanged = { /* URL changed; CobaltApiClient reads from SettingsRepository live */ }
                sheet.show(supportFragmentManager, SettingsSheet.TAG)
            }
        }
    }
```
with:
```kotlin
    private fun setupSettings() {
        binding.tvSettings.setOnClickListener {
            SettingsSheet.newInstance().also { sheet ->
                sheet.onCobaltUrlChanged = { /* URL changed; CobaltApiClient reads from SettingsRepository live */ }
                sheet.onCookiesChanged = { NodeServerManager.restartServer(this) }
                sheet.show(supportFragmentManager, SettingsSheet.TAG)
            }
        }
    }
```

> `NodeServerManager.restartServer` is added in Task 8. Until then this line will not compile — implement Tasks 7 and 8 together, or temporarily stub the call. The build verification step below assumes Task 8 is also applied; if building Task 7 in isolation, comment out the `onCookiesChanged` line and restore it in Task 8.

- [ ] Step 7: Build (after Task 8 is also applied, or with the stub note above).

```powershell
.\gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Step 8: Commit.

```bash
git add -A && git commit -m "feat(auth): add AccountsSheet + item/sheet layouts; wire into Settings

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: Env injection — NodeServerManager .env write + main.js loader + CobaltServerService ordering + restartServer

**Files:**
- Modify: `app/src/main/java/com/andrometa/pullout/server/NodeServerManager.kt`
- Modify: `app/src/main/java/com/andrometa/pullout/server/CobaltServerService.kt`
- Modify: `app/src/main/assets/nodejs-project/main.js`

> **Reality note (load-bearing):** nodejs-mobile runs node in the same process and `node::Start` blocks until exit; node **cannot** be cleanly restarted in-process. cobalt also reads `cookies.json` and `process.env` only at startup. Therefore `restartServer` triggers a **full app process restart**: it relaunches `MainActivity` via AlarmManager in ~700ms, then calls `Runtime.getRuntime().exit(0)`. The foreground service + Android relaunch the app, node boots fresh, and the updated `.env`/`cookies.json` take effect. cobalt's `main.js` does NOT import dotenv, so we patch it to read the `.env` file into `process.env` before importing cobalt.

- [ ] Step 1: Patch cobalt's entry point to load the `.env` file. Replace the entire contents of `app/src/main/assets/nodejs-project/main.js` with:

```javascript
// PULLOUT nodejs-mobile entry point
import { readFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

// Load a .env file written by NodeServerManager (COOKIE_PATH, YOUTUBE_SESSION_*).
// cobalt itself does not import dotenv, so we hydrate process.env here first.
try {
    const __dir = dirname(fileURLToPath(import.meta.url));
    const envPath = join(__dir, ".env");
    if (existsSync(envPath)) {
        const lines = readFileSync(envPath, "utf8").split("\n");
        for (let line of lines) {
            line = line.trim();
            if (!line || line.startsWith("#")) continue;
            const eq = line.indexOf("=");
            if (eq <= 0) continue;
            const key = line.slice(0, eq).trim();
            let value = line.slice(eq + 1).trim();
            if (value.length >= 2 &&
                ((value[0] === '"' && value[value.length - 1] === '"') ||
                 (value[0] === "'" && value[value.length - 1] === "'"))) {
                value = value.slice(1, -1);
            }
            if (process.env[key] === undefined) {
                process.env[key] = value;
            }
        }
    }
} catch (e) {
    console.error("[PULLOUT] .env load failed:", e.message || e);
}

process.env.API_URL            = process.env.API_URL            || "http://localhost:9000/";
process.env.API_PORT           = process.env.API_PORT           || "9000";
process.env.API_LISTEN_ADDRESS = process.env.API_LISTEN_ADDRESS || "127.0.0.1";

import("./src/cobalt.js").catch((e) => {
    console.error("[PULLOUT] cobalt boot failed:", e.message || e);
    process.exit(1);
});
```

- [ ] Step 2: Update `NodeServerManager.kt`. Add the imports, the `.env` writer + cookies.json seeder, call them from `prepareNodeProject`, and add `restartServer`.

First, replace the import block at the top of `app/src/main/java/com/andrometa/pullout/server/NodeServerManager.kt`:

```kotlin
import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.janeasystems.nodejsmobile.NodeJsMobile
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
```
with:
```kotlin
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.andrometa.pullout.MainActivity
import com.andrometa.pullout.auth.PoTokenManager
import com.janeasystems.nodejsmobile.NodeJsMobile
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
```

- [ ] Step 3: In `NodeServerManager.kt`, replace `prepareNodeProject` so it writes `.env` and seeds `cookies.json`. Replace:

```kotlin
        Log.i(TAG, "Extracting nodejs-project (v$assetVersion) to ${targetDir.absolutePath}")
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()
        copyAssetDir(context, "nodejs-project", targetDir)
        Log.i(TAG, "Extraction complete")
        return targetDir.absolutePath
    }
```
with:
```kotlin
        Log.i(TAG, "Extracting nodejs-project (v$assetVersion) to ${targetDir.absolutePath}")
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()
        copyAssetDir(context, "nodejs-project", targetDir)
        Log.i(TAG, "Extraction complete")
        ensureCookiesJson(targetDir)
        writeEnvFile(targetDir)
        return targetDir.absolutePath
    }

    /** Seed an empty cobalt cookies.json if absent (never clobber an existing one). */
    private fun ensureCookiesJson(projectDir: File) {
        val cookies = File(projectDir, "cookies.json")
        if (!cookies.exists()) {
            cookies.writeText("{}")
            Log.i(TAG, "seeded empty cookies.json")
        }
    }

    /**
     * Write the .env consumed by main.js -> cobalt. Reserves the po_token port
     * first so YOUTUBE_SESSION_SERVER points at the live PoTokenServer.
     */
    private fun writeEnvFile(projectDir: File) {
        PoTokenManager.reservePort()
        val cookiePath = File(projectDir, "cookies.json").absolutePath
        val env = buildString {
            appendLine("API_URL=http://localhost:$PORT/")
            appendLine("API_PORT=$PORT")
            appendLine("API_LISTEN_ADDRESS=127.0.0.1")
            appendLine("COOKIE_PATH=$cookiePath")
            appendLine("YOUTUBE_SESSION_SERVER=${PoTokenManager.sessionServerUrl}")
            appendLine("YOUTUBE_SESSION_INNERTUBE_CLIENT=WEB_EMBEDDED")
            appendLine("YOUTUBE_SESSION_RELOAD_INTERVAL=300")
        }
        File(projectDir, ".env").writeText(env)
        Log.i(TAG, "wrote .env (session server ${PoTokenManager.sessionServerUrl})")
    }
```

- [ ] Step 4: In `NodeServerManager.kt`, add `restartServer` immediately before the closing `}` of the `object NodeServerManager` (after `fun isReady()`):

```kotlin
    /**
     * cobalt reads cookies.json + process.env only at startup, and nodejs-mobile
     * cannot restart node in-process. So we relaunch MainActivity via AlarmManager
     * and hard-exit the process; Android brings the app back with a fresh node.
     */
    fun restartServer(context: Context) {
        Log.w(TAG, "restartServer: scheduling full process restart to reload cookies/env")
        // Re-write .env/cookies seed against the (already-extracted) project dir.
        runCatching {
            val projectDir = File(context.filesDir, "nodejs-project")
            if (projectDir.exists()) {
                ensureCookiesJson(projectDir)
                writeEnvFile(projectDir)
            }
        }
        scheduleRestart(context.applicationContext)
    }

    private fun scheduleRestart(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(context, RESTART_REQUEST_CODE, intent, flags)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.RTC, System.currentTimeMillis() + 700L, pending)
        Log.w(TAG, "process exiting in 700ms for restart")
        Runtime.getRuntime().exit(0)
    }
```

Then add this constant next to the other `private const val`s near the top of the object (after `HEALTH_TIMEOUT_MS`):
```kotlin
    private const val RESTART_REQUEST_CODE = 0xC0BA17
```

- [ ] Step 5: Update `CobaltServerService.kt` to fix ordering: reserve the po_token port, start the node server (which writes `.env` using the reserved port), then bring up the WebView/server via `PoTokenManager.start`. Replace the body of `onStartCommand`'s post-`startForeground` section.

In `app/src/main/java/com/andrometa/pullout/server/CobaltServerService.kt`, replace:
```kotlin
        NodeServerManager.startServer(this)
        return START_STICKY  // Restart service if killed
```
with:
```kotlin
        // 1) Reserve the po_token loopback port so YOUTUBE_SESSION_SERVER is known
        //    BEFORE NodeServerManager writes the .env file.
        com.andrometa.pullout.auth.PoTokenManager.reservePort()
        // 2) Start cobalt (writes .env using the reserved port, then boots node).
        NodeServerManager.startServer(this)
        // 3) Bring up the po_token HTTP server + hidden WebView.
        com.andrometa.pullout.auth.PoTokenManager.start(applicationContext)
        return START_STICKY  // Restart service if killed
```

- [ ] Step 6: Bump the asset version so the modified `main.js` re-extracts on next launch. Read `app/src/main/assets/nodejs-project/pullout-version.txt`, then increment it (e.g. append `-auth` or bump the number). Use this command to set a deterministic new version:

```powershell
Set-Content -NoNewline -Path "app\src\main\assets\nodejs-project\pullout-version.txt" -Value "auth-1"
```
> If `pullout-version.txt` does not exist, create it with the value `auth-1`. This forces `prepareNodeProject` to re-extract assets (including the patched `main.js`) on the device.

- [ ] Step 7: Build.

```powershell
.\gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Step 8: Commit.

```bash
git add -A && git commit -m "feat(auth): inject COOKIE_PATH/YOUTUBE_SESSION env via .env; restartServer via process relaunch

- main.js hydrates process.env from .env before booting cobalt
- NodeServerManager writes .env + seeds cookies.json; reserves po_token port first
- CobaltServerService orders reservePort -> startServer -> PoTokenManager.start
- restartServer relaunches MainActivity + exits process (node can't hot-restart)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 9: Build, install, and verify on device

**Files:**
- (verification only; commit any fixes)

- [ ] Step 1: Clean-build the debug APK.

```powershell
.\gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] Step 2: Install on the device `R5CT31LB06P`.

```powershell
.\gradlew installDebug
```
Expected: `Installed on 1 device.` If `installDebug` targets the wrong device, use:
```powershell
adb -s R5CT31LB06P install -r app\build\outputs\apk\debug\app-debug.apk
```

- [ ] Step 3: Launch the app and let it boot the server.

```powershell
adb -s R5CT31LB06P shell am force-stop com.andrometa.pullout
adb -s R5CT31LB06P shell am start -n com.andrometa.pullout/.MainActivity
```

- [ ] Step 4: Dump logcat to a FILE (not streaming — avoids ADB socket exhaustion), then inspect it. Wait ~40s after launch for cobalt + po_token to come up.

```powershell
adb -s R5CT31LB06P logcat -d > "D:\Projects\PULLOUT\boot-log.txt"
```
Then read `D:\Projects\PULLOUT\boot-log.txt` and confirm:
- cobalt boot lines (no `cobalt boot failed`).
- `wrote .env (session server http://127.0.0.1:9001/)` from `NodeServerManager`.
- `PoTokenServer listening on 127.0.0.1:9001` from `PoTokenManager`.
- Eventually `accepted poToken (len=...)` from `PoTokenBridge` (len ≥ 160) AND cobalt's `poToken & visitor_data loaded successfully!`.

> If the matching `scripts/test-boot.ps1` helper exists in the repo, prefer running it (it already does a file-based dump + grep). Otherwise the commands above are the canonical pattern.

- [ ] Step 5: Probe `/get_pot` directly over loopback on the device. Before any token it returns 503; once the WebView harvests it, 200 with the JSON.

```powershell
adb -s R5CT31LB06P shell "curl -s -m 5 -X POST http://127.0.0.1:9001/get_pot"
```
Expected (after ~30s): `{"potoken":"...","visitor_data":"...","updated":<ms>}` with a potoken ≥ 160 chars. Earlier it may print `{"error":"no token yet"}` (HTTP 503) — that is the expected pre-token state.

- [ ] Step 6: Manual login smoke (document results; do not script the WebView typing). On the device:
  1. Open the app → tap settings (`⋯`) → tap `accounts`.
  2. The AccountsSheet lists youtube / instagram / twitter-x / reddit, each `NOT CONNECTED`.
  3. Tap `[ LOG IN ]` on youtube → log in inside the WebView.
  4. On success the activity closes; the app process restarts (expected, per Task 8).
  5. Re-open accounts → youtube shows `CONNECTED` (green dot).
  6. Verify cookies.json gained the youtube key:
     ```powershell
     adb -s R5CT31LB06P shell "run-as com.andrometa.pullout cat files/nodejs-project/cookies.json"
     ```
     Expected: a JSON object containing `"youtube": ["...SID=...; ...SAPISID=..."]` (or `__Secure-1PSID`).
  7. Confirm po_token still populates after the restart (repeat Step 5).

- [ ] Step 7: If any verification step fails, debug using superpowers:systematic-debugging, fix inline, rebuild, re-verify, and commit each fix:

```bash
git add -A && git commit -m "fix(auth): <describe fix>

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] Step 8: Tag, push, and release once verification passes.

```powershell
git tag v1.4-auth
git push origin HEAD
git push origin v1.4-auth
gh release create v1.4-auth app\build\outputs\apk\debug\app-debug.apk --title "v1.4-auth" --notes "Cookie login (YouTube/Instagram/Twitter-X/Reddit) + on-device YouTube po_token generation (faithful yt-session-generator port). cobalt env injected via .env; po_token served at 127.0.0.1:9001/get_pot."
```
Expected: tag pushed, GitHub release created with the APK attached.

---

## Self-review (spec coverage, placeholders, types)

- **Spec coverage:** Cookie login (LoginActivity + CookieStore, Task 5–6), cookies.json shape `{id:[str]}` (CookieStore.saveService/toCobaltJson), COOKIE_PATH + YOUTUBE_SESSION_SERVER + YOUTUBE_SESSION_INNERTUBE_CLIENT=WEB_EMBEDDED env injection (Task 8 .env + main.js loader, verified against `core/env.js` key names), po_token capture via fetch/XHR wrap of `/youtubei/v1/player` extracting `context.client.visitorData` + `serviceIntegrityDimensions.poToken` (Task 2), NanoHTTPD `/get_pot` returning `{potoken,visitor_data,updated}` with 503-before-ready (Task 1, matches youtube-session.js), ≥160-char validation (PoTokenBridge + youtube-session.js), 30-min refresh + backoff (PoTokenManager), AccountsSheet with 4 services + login/logout + status dots (Task 7), all four services with exact domains/required/relevant keys per spec (AccountStatus registry), restart-on-cookie-change (restartServer → process relaunch). All spec components mapped.
- **Placeholder scan:** No `TODO`, `...`, `<placeholder>`, or "similar to" remain in code blocks — every Kotlin/JS/XML block is complete and self-contained.
- **Type consistency:** `PoTokenServer.TokenInfo` fields (`potoken: String`, `visitorData: String`, `updated: Long`) match `serializeToken`, the bridge callback `(String, String) -> Unit`, and `PoTokenManager`'s `TokenInfo(...)` construction. `ServiceConfig`/`AuthServices.byId` consumed consistently by CookieStore, LoginActivity, AccountsSheet. `onCookiesChanged: (() -> Unit)?` threaded SettingsSheet → MainActivity → `NodeServerManager.restartServer(Context)`. `sessionServerUrl: String` reserved synchronously before `.env` write. View-binding class names (`ActivityLoginBinding`, `SheetAccountsBinding`, `ItemAccountBinding`) derive from the created layout files. Manifest activity name `.auth.LoginActivity` matches the package.
- **Cross-task ordering caveat surfaced:** Task 7 Step 6 references `NodeServerManager.restartServer` (added in Task 8); the note instructs implementing 7+8 together or stubbing — flagged so an agent doesn't hit an unexplained compile error.

**Plan path:** `D:\Projects\PULLOUT\docs\superpowers\plans\2026-06-08-pullout-auth.md`
**Task count:** 9
