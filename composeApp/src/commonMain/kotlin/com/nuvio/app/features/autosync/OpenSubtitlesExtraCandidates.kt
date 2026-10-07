package com.nuvio.app.features.autosync

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.SubtitleLanguageMatching
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Open Stremio OpenSubtitles addon — same contract as Android AutoSync coordinator. */
internal object OpenSubtitlesExtraCandidates {
    private const val ADDON_BASE = "https://opensubtitles-v3.strem.io"
    private const val SPANISH_SYNC_LANGUAGE = "es"
    const val MAX_EXTRA_CANDIDATES = 5

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Response(
        val subtitles: List<Entry> = emptyList(),
    )

    @Serializable
    private data class Entry(
        val url: String = "",
        val lang: String = "",
        @SerialName("id") val id: String? = null,
    )

    suspend fun load(type: String, videoId: String): List<AutoSyncSubtitleCandidate> {
        val trimmedId = videoId.trim()
        if (trimmedId.isEmpty()) return emptyList()
        val canonicalType = if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()
        val url = "$ADDON_BASE/subtitles/$canonicalType/$trimmedId.json"
        return runCatching {
            val body = httpGetTextWithHeaders(url = url, headers = emptyMap())
            val array = json.decodeFromString<Response>(body).subtitles
            array.mapNotNull { item ->
                val subtitleUrl = item.url.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val language = item.lang
                if (!SubtitleLanguageMatching.matchesLanguageCode(language, SPANISH_SYNC_LANGUAGE)) {
                    return@mapNotNull null
                }
                AutoSyncSubtitleCandidate(
                    url = subtitleUrl,
                    language = language,
                    name = "OpenSubtitles",
                )
            }.distinctBy { it.url }.take(MAX_EXTRA_CANDIDATES)
        }.getOrElse { emptyList() }
    }
}
