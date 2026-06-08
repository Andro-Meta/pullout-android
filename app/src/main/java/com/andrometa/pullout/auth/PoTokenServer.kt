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
