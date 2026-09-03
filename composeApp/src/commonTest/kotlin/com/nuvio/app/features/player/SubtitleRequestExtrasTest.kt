package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleRequestExtrasTest {
    @Test
    fun omitsExtraPathWhenHintsAreMissing() {
        assertNull(buildSubtitleExtraPathSegment())
        assertNull(buildSubtitleExtraPathSegment(videoHash = "  ", videoSize = 0L, filename = ""))
    }

    @Test
    fun buildsVideoHashSizeFilenameAndEmbeddedSpanishSegment() {
        assertEquals(
            "videoHash=abc&videoSize=10&filename=a.mkv&hasEmbeddedSpanish=false",
            buildSubtitleExtraPathSegment(
                videoHash = "abc",
                videoSize = 10L,
                filename = "a.mkv",
                hasEmbeddedSpanish = false,
            ),
        )
    }

    @Test
    fun embeddedSpanishHelperDetectsVariants() {
        assertTrue(
            SubtitleLanguageMatching.isEmbeddedSpanishLanguage("spa"),
        )
        assertTrue(
            SubtitleLanguageMatching.isEmbeddedSpanishLanguage("es-419"),
        )
        assertTrue(
            SubtitleLanguageMatching.isEmbeddedSpanishSubtitleTrack(
                SubtitleTrack(
                    index = 0,
                    id = "sub1",
                    label = "Español",
                    language = "spa",
                ),
            ),
        )
        assertTrue(
            !SubtitleLanguageMatching.isEmbeddedSpanishLanguage("eng"),
        )
    }
}
