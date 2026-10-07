package com.nuvio.app.features.autosync

import com.nuvio.app.core.build.ApachiyProductSettings
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.embedded.DialogueTimingIndex
import com.nuvio.app.features.player.embedded.EmbeddedTextCodec
import com.nuvio.app.features.player.embedded.EmbeddedTextTrack

internal data class CommunityAutoSyncAttempt(
    val subtitleUrl: String,
    val cues: List<SubtitleSyncCue>,
    val timeline: AutoSyncTimelineRetimeResult,
)

internal object CommunityAutoSyncRetime {
    suspend fun retimeAgainstIndex(
        index: DialogueTimingIndex,
        subtitleUrl: String,
        headers: Map<String, String>,
        extraSubtitleUrls: List<String> = emptyList(),
    ): CommunityAutoSyncAttempt? {
        val primary = attemptOne(index.tracks, subtitleUrl, headers) ?: run {
            val extras = extraSubtitleUrls.filter { it.isNotBlank() && it != subtitleUrl }
            extras.firstNotNullOfOrNull { url -> attemptOne(index.tracks, url, headers) }
        }
        return primary
    }

    private suspend fun attemptOne(
        references: List<EmbeddedTextTrack>,
        subtitleUrl: String,
        headers: Map<String, String>,
    ): CommunityAutoSyncAttempt? {
        val body = runCatching {
            httpGetTextWithHeaders(url = subtitleUrl, headers = headers)
        }.getOrNull() ?: return null
        val cues = PlayerSubtitleCueParser.parse(body, subtitleUrl)
        val timeline = AutoSyncSelectedRun.retime(references, cues) ?: return null
        return CommunityAutoSyncAttempt(subtitleUrl = subtitleUrl, cues = cues, timeline = timeline)
    }

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
