package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertEquals

class StartupSubtitleRouteTest {
    @Test
    fun embeddedSpanishSkipsAllFetch() {
        assertEquals(
            StartupSubtitleRoute.NONE,
            decideStartupSubtitleRoute(
                hasEmbeddedSpanish = true,
                autoSyncAvailable = true,
                noEmbeddedTextTracks = false,
            ),
        )
    }

    @Test
    fun noAutoSyncGoesToCommunity() {
        assertEquals(
            StartupSubtitleRoute.COMMUNITY,
            decideStartupSubtitleRoute(
                hasEmbeddedSpanish = false,
                autoSyncAvailable = false,
                noEmbeddedTextTracks = false,
            ),
        )
    }

    @Test
    fun noEmbeddedTracksGoesToCommunity() {
        assertEquals(
            StartupSubtitleRoute.COMMUNITY,
            decideStartupSubtitleRoute(
                hasEmbeddedSpanish = false,
                autoSyncAvailable = true,
                noEmbeddedTextTracks = true,
            ),
        )
    }

    @Test
    fun openSubtitlesFirstWhenSyncAvailableAndEmbeddedRefs() {
        assertEquals(
            StartupSubtitleRoute.OPEN_SUBTITLES_FIRST,
            decideStartupSubtitleRoute(
                hasEmbeddedSpanish = false,
                autoSyncAvailable = true,
                noEmbeddedTextTracks = false,
            ),
        )
    }
}
