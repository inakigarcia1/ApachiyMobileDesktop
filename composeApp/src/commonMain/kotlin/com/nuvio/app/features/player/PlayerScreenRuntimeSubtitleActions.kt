package com.nuvio.app.features.player

import com.nuvio.app.core.i18n.localizedNoSubtitleLinesFound
import com.nuvio.app.core.i18n.localizedSubtitleLinesLoadError
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.agentqa.AgentQa
import com.nuvio.app.features.player.embedded.selectPreferredSpanishAddonSubtitle
import com.nuvio.app.isIos
import kotlinx.coroutines.launch

internal fun PlayerScreenRuntime.applyAddonSubtitleUri(url: String) {
    val prepared = preparedAddonSubtitlePlaybackUri
    val readyController = playerController
    if (prepared != null && readyController != null) {
        readyController.selectSubtitleTrack(-1)
        readyController.setSubtitleUri(prepared)
        appliedAddonSubtitleUrl = url
        launchCommunityAutoSync(url)
        agentQaPipeline = "applied"
        AgentQa.event("pipeline", "applied")
        publishAgentQaSnapshot()
        return
    }
    if (addonSubtitleApplyInFlightUrl == url) {
        return
    }
    addonSubtitleApplyInFlightUrl = url
    scope.launch {
        try {
            val cacheKey = buildAddonSubtitleCacheKey(
                remoteUrl = url,
                videoHash = activeVideoHash,
                videoSize = activeVideoSize,
                filename = activeTorrentFilename,
            )
            agentQaCacheKey = cacheKey
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
                appliedAddonSubtitleUrl = url
                launchCommunityAutoSync(url)
                if (AgentQa.enabled) {
                    captureAppliedSubtitleForAgentQa(url)
                }
                agentQaPipeline = "applied"
                AgentQa.event("pipeline", "applied")
            } else {
                agentQaPipeline = "failed"
                agentQaSkipReason = agentQaSkipReason ?: "subtitle_download_failed"
                AgentQa.event("pipeline", "subtitle_download_failed")
            }
            publishAgentQaSnapshot()
        } finally {
            if (addonSubtitleApplyInFlightUrl == url) {
                addonSubtitleApplyInFlightUrl = null
            }
        }
    }
}

internal fun PlayerScreenRuntime.fetchAddonSubtitlesForActiveItem() {
    subtitlePipelineJob?.cancel()
    subtitlePipelineJob = scope.launch {
        fetchAddonSubtitlesPipeline()
    }
}

internal suspend fun PlayerScreenRuntime.fetchAddonSubtitlesPipeline() {
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() }
    val videoId = activeVideoId?.takeIf { it.isNotBlank() }
    if (type == null || videoId == null) {
        finishAgentQaPipeline(skipReason = "missing_ids")
        return
    }
    if (SubtitleLanguageMatching.hasEmbeddedSpanishSubtitleTrack(subtitleTracks)) {
        SubtitleRepository.clear()
        agentQaHasEmbeddedSpanish = true
        finishAgentQaPipeline(skipReason = "embedded_spanish")
        return
    }
    val sourceHeaders = sanitizePlaybackHeaders(activeSourceHeaders)
    autoFetchedAddonSubtitlesForKey = addonSubtitleFetchKey
    if (!isIos) {
        val timing = loadDialogueTimingIndex()
        if (timing.hasEmbeddedSpanish) {
            SubtitleRepository.clear()
            agentQaHasEmbeddedSpanish = true
            finishAgentQaPipeline(skipReason = "embedded_spanish")
            return
        }
    }

    agentQaPipeline = "fetching"
    AgentQa.event("pipeline", agentQaPipeline)
    val addonSubs = SubtitleRepository.fetchApachiySubtitles(
        type = type,
        videoId = videoId,
        videoHash = activeVideoHash,
        videoSize = activeVideoSize,
        filename = activeTorrentFilename,
    )
    AgentQa.event(
        "addon_subs",
        "n=${addonSubs.size} langs=${addonSubs.joinToString(",") { it.language }}",
    )

    val selected = if (isUserExplicitSubtitleSelection) {
        null
    } else {
        selectPreferredSpanishAddonSubtitle(addonSubs)
    }
    if (selected != null) {
        val resolved = resolvePlaybackSubtitleUri(
            remoteUrl = selected.url,
            sourceHeaders = sourceHeaders,
            cacheKey = buildAddonSubtitleCacheKey(
                remoteUrl = selected.url,
                videoHash = activeVideoHash,
                videoSize = activeVideoSize,
                filename = activeTorrentFilename,
            ),
        )
        if (resolved != null) {
            preparedAddonSubtitlePlaybackUri = resolved
            preferredSubtitleSelectionApplied = true
            selectedAddonSubtitleId = selected.id
            selectedSubtitleIndex = -1
            useCustomSubtitles = true
            agentQaCacheKey = buildAddonSubtitleCacheKey(
                remoteUrl = selected.url,
                videoHash = activeVideoHash,
                videoSize = activeVideoSize,
                filename = activeTorrentFilename,
            )
            if (playerController != null) {
                applyAddonSubtitleUri(selected.url)
            } else {
                agentQaPipeline = "waiting_player"
            }
        }
    }
    subtitlePipelineDone = true
    tryCompleteOpeningOverlay()
    publishAgentQaSnapshot()
}

private fun PlayerScreenRuntime.finishAgentQaPipeline(
    skipReason: String,
    donePipeline: String = "skipped",
) {
    agentQaSkipReason = skipReason
    agentQaPipeline = donePipeline
    subtitlePipelineDone = true
    AgentQa.event("pipeline", skipReason)
    tryCompleteOpeningOverlay()
    publishAgentQaSnapshot()
}

private suspend fun PlayerScreenRuntime.captureAppliedSubtitleForAgentQa(url: String) {
    val body = runCatching {
        httpGetTextWithHeaders(
            url = url,
            headers = sanitizePlaybackHeaders(activeSourceHeaders),
        )
    }.getOrNull() ?: return
    AgentQa.writeBytes("applied.srt", body.encodeToByteArray())
    agentQaAppliedCues = PlayerSubtitleCueParser.parse(body, url)
    refreshAgentQaSyncScore()
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
