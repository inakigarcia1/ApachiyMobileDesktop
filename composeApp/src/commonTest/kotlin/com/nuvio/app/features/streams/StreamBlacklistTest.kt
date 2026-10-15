package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamBlacklistTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun apachiyMovieUsesBingeGroupHash() {
        val request = streamBlacklistTarget(
            addonId = "addon:com.apachiy.addon:https://api.example/apachiy/manifest.json",
            bingeGroup = "comet|realdebrid|$hash".uppercase(),
            type = "movie",
            videoId = "tt0111161",
            parentMetaId = "",
            seasonNumber = null,
            episodeNumber = null,
        )
        assertEquals(
            StreamBlacklistRequest(mediaType = "movie", mediaId = "tt0111161", infoHash = hash),
            request,
        )
    }

    @Test
    fun otherAddonHasNoTarget() {
        assertNull(
            streamBlacklistTarget(
                addonId = "addon:com.torrentio.addon:https://torrentio.strem.fun/manifest.json",
                bingeGroup = "comet|realdebrid|$hash",
                type = "movie",
                videoId = "tt0111161",
                parentMetaId = "",
                seasonNumber = null,
                episodeNumber = null,
            ),
        )
    }

    @Test
    fun linkWithoutHashHasNoTarget() {
        assertNull(
            streamBlacklistTarget(
                addonId = "addon:com.apachiy.addon:https://api.example/apachiy/manifest.json",
                bingeGroup = "comet|1080p",
                type = "movie",
                videoId = "tt0111161",
                parentMetaId = "",
                seasonNumber = null,
                episodeNumber = null,
            ),
        )
    }

    @Test
    fun seriesUsesParentIdAndEpisode() {
        val request = streamBlacklistTarget(
            addonId = "addon:com.apachiy.addon:https://api.example/manifest.json",
            bingeGroup = "comet|torbox|$hash",
            type = "tv",
            videoId = "tt0098844:1:5",
            parentMetaId = "tt0098844",
            seasonNumber = 1,
            episodeNumber = 5,
        )
        assertEquals(
            StreamBlacklistRequest(
                mediaType = "series",
                mediaId = "tt0098844",
                infoHash = hash,
                season = 1,
                episode = 5,
            ),
            request,
        )
    }
}
