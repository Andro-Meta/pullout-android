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
