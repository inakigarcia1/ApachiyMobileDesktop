package com.nuvio.app.features.autosync

import com.nuvio.app.core.build.ApachiyProductSettings
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.embedded.DialogueTimingIndex
import com.nuvio.app.features.player.embedded.EmbeddedTextCodec
import com.nuvio.app.features.player.embedded.EmbeddedTextTrack
import com.nuvio.app.features.player.embedded.hasEmbeddedSpanishTextTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal data class CommunityAutoSyncAttempt(
    val subtitleUrl: String,
    val cues: List<SubtitleSyncCue>,
    val timeline: AutoSyncTimelineRetimeResult,
)

internal object CommunityAutoSyncRetime {
    private data class Loaded(val url: String, val cues: List<SubtitleSyncCue>, val agreement: Double)

    /**
     * Downloads every candidate, ranks them by how well their line starts already fit the
     * embedded reference, and returns the best one the matcher accepts. Lists often lead with a
     * release cut for another edit, so taking the first confident match picked badly synced files.
     */
    suspend fun retimeAgainstIndex(
        index: DialogueTimingIndex,
        subtitleUrl: String,
        headers: Map<String, String>,
        extraSubtitleUrls: List<String> = emptyList(),
    ): CommunityAutoSyncAttempt? {
        val urls = (listOf(subtitleUrl) + extraSubtitleUrls).filter { it.isNotBlank() }.distinct()
        val referenceStarts = rankingReference(index.tracks)?.cues?.map { it.startMs }.orEmpty()
        val loaded = coroutineScope {
            urls.map { url ->
                async { load(url, if (url == subtitleUrl) headers else emptyMap(), referenceStarts) }
            }.awaitAll()
        }.filterNotNull()
        // Stable sort: equal scores keep the selected subtitle first.
        val ranked = loaded.sortedByDescending { it.agreement }
        for (candidate in ranked) {
            val timeline = AutoSyncSelectedRun.retime(index.tracks, candidate.cues) ?: continue
            return CommunityAutoSyncAttempt(subtitleUrl = candidate.url, cues = candidate.cues, timeline = timeline)
        }
        return null
    }

    private suspend fun load(
        subtitleUrl: String,
        headers: Map<String, String>,
        referenceStarts: List<Long>,
    ): Loaded? {
        val body = runCatching {
            httpGetTextWithHeaders(url = subtitleUrl, headers = headers)
        }.getOrNull() ?: return null
        val cues = PlayerSubtitleCueParser.parse(body, subtitleUrl)
        val agreement = AutoSyncOnsetAgreement.score(referenceStarts, cues.map { it.startTimeMs })
        return Loaded(subtitleUrl, cues, agreement)
    }

    /** The densest non-Spanish text track: what the matcher will most likely align against. */
    private fun rankingReference(tracks: List<EmbeddedTextTrack>): EmbeddedTextTrack? =
        tracks.filter { it.codec != EmbeddedTextCodec.Pgs && !hasEmbeddedSpanishTextTrack(listOf(it)) && !it.forced }
            .maxByOrNull { it.cues.size }
            ?: tracks.maxByOrNull { it.cues.size }

    fun indexNeedsPgsSemanticFallback(
        index: DialogueTimingIndex,
        attempt: CommunityAutoSyncAttempt?,
    ): Boolean {
        if (attempt != null) return false
        if (index.tracks.isEmpty()) return false
        return index.tracks.all { it.codec == EmbeddedTextCodec.Pgs }
    }
}

internal fun effectiveSyncToleranceMsForCommunity(): Int =
    effectiveSyncToleranceMs(
        operatorSettingsVisible = ApachiyProductSettings.operatorSettingsVisible,
        storedToleranceMs = AutoSyncPreferencesRepository.syncToleranceMs.value,
    )
