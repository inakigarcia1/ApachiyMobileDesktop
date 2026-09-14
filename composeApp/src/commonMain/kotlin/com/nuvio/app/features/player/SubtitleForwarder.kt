package com.nuvio.app.features.player

import com.nuvio.app.features.player.embedded.EmbeddedSubtitleExtractor
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
                val identity = sourceUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    runCatching {
                        EmbeddedSubtitleExtractor.probeFileIdentity(url, headers, videoSize)
                    }.getOrNull()
                }
                val listingFilename = identity?.filename ?: filename
                val listingSize = identity?.sizeBytes ?: videoSize
                val extract = sourceUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    runCatching { EmbeddedSubtitleExtractor.extract(url, headers, listingSize) }.getOrNull()
                }
                if (extract?.hasEmbeddedSpanish == true) {
                    return@withTimeoutOrNull emptyList()
                }

                SubtitleRepository.fetchAddonSubtitles(
                    type = type,
                    videoId = videoId,
                    videoHash = videoHash,
                    videoSize = listingSize,
                    filename = listingFilename,
                    hasEmbeddedSpanish = false,
                    reference = extract?.reference,
                    sourceHeaders = headers,
                ).join()

                val allSubtitles = SubtitleRepository.addonSubtitles.value

                val filtered = allSubtitles.filter { subtitle ->
                    languageMatchesPreference(subtitle.language, preferredLanguage) ||
                        (secondaryLanguage != null &&
                            languageMatchesPreference(subtitle.language, secondaryLanguage))
                }

                filtered.map { subtitle ->
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
