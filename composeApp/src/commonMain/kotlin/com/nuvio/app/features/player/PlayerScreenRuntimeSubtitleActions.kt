package com.nuvio.app.features.player

import com.nuvio.app.core.i18n.localizedNoSubtitleLinesFound
import com.nuvio.app.core.i18n.localizedSubtitleLinesLoadError
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.embedded.EmbeddedSubtitleExtractor
import kotlinx.coroutines.launch

internal fun PlayerScreenRuntime.applyAddonSubtitleUri(url: String) {
    scope.launch {
        val cacheKey = buildAddonSubtitleCacheKey(
            remoteUrl = url,
            videoHash = activeVideoHash,
            videoSize = activeVideoSize,
            filename = activeTorrentFilename,
        )
        val resolved = resolvePlaybackSubtitleUri(
            remoteUrl = url,
            sourceHeaders = sanitizePlaybackHeaders(activeSourceHeaders),
            cacheKey = cacheKey,
        )
        val controller = playerController
        if (controller == null) return@launch
        controller.selectSubtitleTrack(-1)
        if (resolved != null) {
            controller.setSubtitleUri(resolved)
        }
    }
}

internal fun PlayerScreenRuntime.fetchAddonSubtitlesForActiveItem() {
    scope.launch {
        fetchAddonSubtitlesPipeline()
    }
}

internal suspend fun PlayerScreenRuntime.fetchAddonSubtitlesPipeline() {
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() }
    val videoId = activeVideoId?.takeIf { it.isNotBlank() }
    if (type == null || videoId == null) {
        subtitlePipelineDone = true
        tryCompleteOpeningOverlay()
        return
    }
    if (SubtitleLanguageMatching.hasEmbeddedSpanishSubtitleTrack(subtitleTracks)) {
        SubtitleRepository.clear()
        subtitlePipelineDone = true
        tryCompleteOpeningOverlay()
        return
    }
    val extract = runCatching {
        EmbeddedSubtitleExtractor.extract(
            sourceUrl = activeSourceUrl,
            headers = sanitizePlaybackHeaders(activeSourceHeaders),
        )
    }.getOrNull()
    if (extract?.hasEmbeddedSpanish == true) {
        SubtitleRepository.clear()
        subtitlePipelineDone = true
        tryCompleteOpeningOverlay()
        return
    }
    SubtitleRepository.fetchAddonSubtitles(
        type = type,
        videoId = videoId,
        videoHash = activeVideoHash,
        videoSize = activeVideoSize,
        filename = activeTorrentFilename,
        hasEmbeddedSpanish = false,
        reference = extract?.reference,
        sourceHeaders = sanitizePlaybackHeaders(activeSourceHeaders),
    ).join()
    subtitlePipelineDone = true
    tryCompleteOpeningOverlay()
}

internal fun PlayerScreenRuntime.setSubtitleDelay(delayMs: Int) {
    val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    subtitleDelayMs = clamped
    PlayerTrackPreferenceStorage.saveSubtitleDelayMs(playbackSession.videoId, clamped)
    playerController?.setSubtitleDelayMs(clamped)
}

internal fun PlayerScreenRuntime.loadSubtitleAutoSyncCues(force: Boolean = false) {
    val subtitle = selectedAddonSubtitle ?: return
    if (!force && subtitleAutoSyncState.cues.isNotEmpty()) return
    subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = true, errorMessage = null)
    scope.launch {
        val result = runCatching {
            val body = httpGetTextWithHeaders(
                url = subtitle.url,
                headers = sanitizePlaybackHeaders(activeSourceHeaders),
            )
            PlayerSubtitleCueParser.parse(body, subtitle.url)
        }
        result.fold(
            onSuccess = { cues ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    cues = cues,
                    isLoading = false,
                    errorMessage = if (cues.isEmpty()) localizedNoSubtitleLinesFound() else null,
                )
            },
            onFailure = { error ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    isLoading = false,
                    errorMessage = error.message ?: localizedSubtitleLinesLoadError(),
                )
            },
        )
    }
}

internal fun PlayerScreenRuntime.captureSubtitleAutoSyncTime() {
    subtitleAutoSyncState = subtitleAutoSyncState.copy(
        capturedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
        errorMessage = null,
    )
    loadSubtitleAutoSyncCues()
}

internal fun PlayerScreenRuntime.applySubtitleAutoSyncCue(cue: SubtitleSyncCue) {
    val capturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: return
    val newDelayMs = (capturedPositionMs - cue.startTimeMs - SUBTITLE_AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    setSubtitleDelay(newDelayMs)
}
