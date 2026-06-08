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
