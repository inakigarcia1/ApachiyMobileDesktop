package com.nuvio.app.features.player

import com.nuvio.app.features.player.embedded.EmbeddedSubtitleExtractor
import com.nuvio.app.isIos
import kotlinx.coroutines.withTimeoutOrNull

object SubtitleForwarder {

    /**
     * Fetches addon subtitles for the given content and filters them by the user's
     * preferred and secondary language. Returns null on failure or timeout for
     * graceful degradation (external player launches without subtitles).
     */
    suspend fun fetchForExternalPlayer(
        type: String,
        videoId: String,
        preferredLanguage: String,
        secondaryLanguage: String?,
        timeoutMs: Long = 20_000L,
        sourceUrl: String? = null,
        sourceHeaders: Map<String, String> = emptyMap(),
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
    ): List<SubtitleInput>? {
        return try {
            withTimeoutOrNull(timeoutMs) {
                val headers = sanitizePlaybackHeaders(sourceHeaders)
                val timing = if (isIos) {
                    null
                } else {
                    sourceUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        runCatching {
                            EmbeddedSubtitleExtractor.loadDialogueTiming(url, headers)
                        }.getOrNull()
                    }
                }
                if (timing?.hasEmbeddedSpanish == true) {
                    return@withTimeoutOrNull emptyList()
                }
                val allSubtitles = SubtitleRepository.fetchApachiySubtitles(
                    type = type,
                    videoId = videoId,
                    videoHash = videoHash,
                    videoSize = videoSize,
                    filename = filename,
                )
                allSubtitles.filter { subtitle ->
                    languageMatchesPreference(subtitle.language, preferredLanguage) ||
                        (secondaryLanguage != null &&
                            languageMatchesPreference(subtitle.language, secondaryLanguage))
                }.map { subtitle ->
                    SubtitleInput(
                        url = subtitle.url,
                        name = subtitle.display,
                        lang = subtitle.language,
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
