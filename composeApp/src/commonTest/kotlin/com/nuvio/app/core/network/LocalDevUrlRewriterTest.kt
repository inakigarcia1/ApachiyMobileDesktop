package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals

private const val API_ORIGIN = "http://localhost:10050"

class LocalDevUrlRewriterTest {
    @Test
    fun rewritesEmulatorHostAndDowngradesLoopbackHttps() {
        assertEquals(
            "http://localhost:10081/s/test",
            rewriteEmulatorLoopbackUrl("http://10.0.2.2:10081/s/test", API_ORIGIN),
        )
        assertEquals(
            "http://localhost:10050/metadata-ai/img?u=https://example.test/p.jpg",
            rewriteEmulatorLoopbackUrl("https://10.0.2.2:10050/metadata-ai/img?u=https://example.test/p.jpg", API_ORIGIN),
        )
    }

    @Test
    fun redirectsApiTlsPortToConfiguredApiOrigin() {
        assertEquals(
            "http://localhost:10050/apachiy/subtitles/proxy/token?t=abc",
            rewriteEmulatorLoopbackUrl("https://10.0.2.2:10051/apachiy/subtitles/proxy/token?t=abc", API_ORIGIN),
        )
        // Stale URLs persisted by earlier builds land on the same origin.
        assertEquals(
            "http://localhost:10050/apachiy/subtitles/proxy/token?t=abc",
            rewriteEmulatorLoopbackUrl("http://localhost:10051/apachiy/subtitles/proxy/token?t=abc", API_ORIGIN),
        )
    }

    @Test
    fun keepsTlsPortWhenNoLocalApiOriginIsConfigured() {
        assertEquals(
            "https://localhost:10051/apachiy/subtitles/proxy/token",
            rewriteEmulatorLoopbackUrl("https://10.0.2.2:10051/apachiy/subtitles/proxy/token", null),
        )
    }

    @Test
    fun leavesNonLoopbackLookalikeHostsUntouched() {
        val url = "https://localhost.example.com:10050/api/thing"
        assertEquals(url, rewriteEmulatorLoopbackUrl(url, API_ORIGIN))
    }

    @Test
    fun leavesPublicUrlsUntouched() {
        val url = "https://image.tmdb.org/t/p/w500/poster.jpg"
        assertEquals(url, rewriteEmulatorLoopbackUrl(url, API_ORIGIN))
    }
}
