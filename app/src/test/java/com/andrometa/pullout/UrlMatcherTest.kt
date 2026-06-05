package com.andrometa.pullout

import com.andrometa.pullout.util.UrlMatcher
import org.junit.Assert.*
import org.junit.Test

class UrlMatcherTest {
    @Test fun youtubeFullUrl() = assertTrue(UrlMatcher.isSupportedUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    @Test fun youtubeShortUrl() = assertTrue(UrlMatcher.isSupportedUrl("https://youtu.be/dQw4w9WgXcQ"))
    @Test fun tiktokUrl() = assertTrue(UrlMatcher.isSupportedUrl("https://www.tiktok.com/@user/video/123"))
    @Test fun twitterUrl() = assertTrue(UrlMatcher.isSupportedUrl("https://twitter.com/user/status/123"))
    @Test fun xUrl() = assertTrue(UrlMatcher.isSupportedUrl("https://x.com/user/status/123"))
    @Test fun instagramUrl() = assertTrue(UrlMatcher.isSupportedUrl("https://www.instagram.com/p/ABC123/"))
    @Test fun redditUrl() = assertTrue(UrlMatcher.isSupportedUrl("https://www.reddit.com/r/videos/comments/abc"))
    @Test fun soundcloudUrl() = assertTrue(UrlMatcher.isSupportedUrl("https://soundcloud.com/artist/track"))
    @Test fun vimeoUrl() = assertTrue(UrlMatcher.isSupportedUrl("https://vimeo.com/123456789"))
    @Test fun twitchUrl() = assertTrue(UrlMatcher.isSupportedUrl("https://www.twitch.tv/channel"))
    @Test fun randomText() = assertFalse(UrlMatcher.isSupportedUrl("hello world"))
    @Test fun nullInput() = assertFalse(UrlMatcher.isSupportedUrl(null))
    @Test fun emptyInput() = assertFalse(UrlMatcher.isSupportedUrl(""))
    @Test fun unsupportedSite() = assertFalse(UrlMatcher.isSupportedUrl("https://google.com/search?q=test"))
    @Test fun bareHostNoScheme() = assertFalse(UrlMatcher.isSupportedUrl("youtube.com/watch?v=abc"))
    @Test fun extractFromProse() = assertEquals("https://youtu.be/abc", UrlMatcher.extractUrl("Check this: https://youtu.be/abc"))
    @Test fun extractFromMultiline() = assertEquals("https://soundcloud.com/a/b", UrlMatcher.extractUrl("Title\nhttps://soundcloud.com/a/b"))
    @Test fun extractStripsTrailingPeriod() = assertEquals("https://youtu.be/abc", UrlMatcher.extractUrl("Watch: https://youtu.be/abc."))
    @Test fun extractReturnsNullWhenNoUrl() = assertNull(UrlMatcher.extractUrl("no link here"))
    @Test fun extractReturnsNullForUnsupported() = assertNull(UrlMatcher.extractUrl("check: https://google.com/search?q=test"))
}
