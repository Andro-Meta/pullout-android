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
