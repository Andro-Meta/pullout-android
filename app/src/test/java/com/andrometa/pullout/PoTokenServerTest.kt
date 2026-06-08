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
