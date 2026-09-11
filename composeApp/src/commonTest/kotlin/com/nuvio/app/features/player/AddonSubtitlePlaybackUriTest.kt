package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonSubtitlePlaybackUriTest {
    @Test
    fun sniffsVttAndAssFromBody() {
        assertEquals("vtt", sniffSubtitleFileExtension("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHola", ""))
        assertEquals(
            "ass",
            sniffSubtitleFileExtension("[Script Info]\nTitle: Test\n\n[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hola", ""),
        )
    }

    @Test
    fun detectsApachiyProxyUrlsAsAuthRequired() {
        assertTrue(
            requiresAuthenticatedSubtitleDownload(
                "https://api.example/apachiy/subtitles/proxy/token?t=abc",
            ),
        )
        assertFalse(requiresAuthenticatedSubtitleDownload("https://example.com/subtitle.srt"))
    }

    @Test
    fun parseRawHttpUrlKeepsEncodedSlashesInPath() {
        val parts = parseRawHttpUrlParts(
            "http://localhost:10051/apachiy/subtitles/proxy/abc%2Fdef?t=ticket",
        )
        requireNotNull(parts)
        assertEquals("/apachiy/subtitles/proxy/abc%2Fdef", parts.encodedPath)
        assertEquals("t=ticket", parts.encodedQuery)
        assertTrue(parts.hasEncodedSlashInPath)
        assertEquals(4, parts.pathSegmentCount)
    }

    @Test
    fun buildCacheKeyIncludesReleaseHints() {
        val key = buildAddonSubtitleCacheKey(
            remoteUrl = "https://example/sub.vtt",
            videoHash = "abc123",
            videoSize = 42L,
            filename = "movie.mkv",
        )
        assertTrue(key.contains("|hash=abc123"))
        assertTrue(key.contains("|size=42"))
        assertTrue(key.contains("|file=movie.mkv"))
    }
}
