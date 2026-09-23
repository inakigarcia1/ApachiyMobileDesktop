package com.nuvio.app.features.player

import com.nuvio.app.features.player.agentqa.AgentQa
import com.nuvio.app.features.player.agentqa.AgentQaActiveCue
import com.nuvio.app.features.player.agentqa.AgentQaSnapshot
import com.nuvio.app.features.player.agentqa.AgentQaSyncScorer
import com.nuvio.app.features.watchprogress.WatchProgressClock

internal fun PlayerScreenRuntime.resetAgentQaPipeline() {
    agentQaPipeline = "extracting"
    agentQaSkipReason = null
    agentQaHasEmbeddedSpanish = false
    agentQaReferenceUsable = false
    agentQaReferenceCueCount = 0
    agentQaCacheKey = null
    agentQaAppliedCues = emptyList()
    agentQaReferenceCues = emptyList()
    agentQaSyncPass = null
    agentQaSyncMedianOffsetMs = null
    agentQaSyncResidualP80Ms = null
    agentQaSyncReason = null
}

internal fun PlayerScreenRuntime.publishAgentQaSnapshot() {
    if (!AgentQa.enabled) return
    val positionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    val active = AgentQaSyncScorer.activeCueAt(
        cues = agentQaAppliedCues.ifEmpty { subtitleAutoSyncState.cues },
        positionMs = positionMs,
        delayMs = subtitleDelayMs,
    )
    val clock = positionMs - subtitleDelayMs
    val covers = active != null && clock in active.startTimeMs..active.endTimeMs
    val selected = selectedAddonSubtitle
    AgentQa.publish(
        AgentQaSnapshot(
            updatedAtEpochMs = WatchProgressClock.nowEpochMs(),
            title = title,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            episodeTitle = activeEpisodeTitle,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = playbackSession.videoId,
            pipeline = agentQaPipeline,
            skipReason = agentQaSkipReason,
            hasEmbeddedSpanish = agentQaHasEmbeddedSpanish,
            referenceUsable = agentQaReferenceUsable,
            referenceCueCount = agentQaReferenceCueCount,
            cacheKey = agentQaCacheKey,
            selectedAddonSubtitleId = selectedAddonSubtitleId,
            selectedAddonLanguage = selected?.language,
            selectedAddonDisplay = selected?.display,
            useCustomSubtitles = useCustomSubtitles,
            subtitleDelayMs = subtitleDelayMs,
            subtitlePipelineDone = subtitlePipelineDone,
            positionMs = positionMs,
            durationMs = playbackSnapshot.durationMs,
            isPlaying = playbackSnapshot.isPlaying,
            isLoading = playbackSnapshot.isLoading,
            errorMessage = errorMessage,
            activeCue = active?.let {
                AgentQaActiveCue(startMs = it.startTimeMs, endMs = it.endTimeMs, text = it.text)
            },
            cueCoversClock = covers,
            syncScorePass = agentQaSyncPass,
            syncMedianOffsetMs = agentQaSyncMedianOffsetMs,
            syncResidualP80Ms = agentQaSyncResidualP80Ms,
            syncReason = agentQaSyncReason,
        ),
    )
}

internal fun PlayerScreenRuntime.applyAgentQaCommand(): Boolean {
    if (!AgentQa.enabled) return false
    val command = AgentQa.claimCommand(listOf("seek", "play", "pause")) ?: return false
    val result = runCatching {
        when (command.action.lowercase()) {
            "seek" -> {
                val target = command.positionMs ?: return@runCatching "missing_positionMs"
                val duration = playbackSnapshot.durationMs
                val clamped = if (duration > 0L) target.coerceIn(0L, duration) else target.coerceAtLeast(0L)
                val controller = playerController ?: return@runCatching "no_player"
                if (!controller.trySeekTo(clamped)) return@runCatching "seek_rejected"
                "ok"
            }
            "play" -> {
                playerController?.play() ?: return@runCatching "no_player"
                shouldPlay = true
                "ok"
            }
            "pause" -> {
                playerController?.pause() ?: return@runCatching "no_player"
                shouldPlay = false
                "ok"
            }
            else -> "unknown_action"
        }
    }.getOrElse { error -> "error:${error.message}" }
    AgentQa.ackCommand(command, result)
    AgentQa.event("command", "${command.action}:$result")
    return true
}

internal fun PlayerScreenRuntime.refreshAgentQaSyncScore() {
    if (!AgentQa.enabled) return
    if (agentQaReferenceCues.isEmpty() || agentQaAppliedCues.isEmpty()) return
    val score = AgentQaSyncScorer.score(agentQaReferenceCues, agentQaAppliedCues)
    agentQaSyncPass = score.pass
    agentQaSyncMedianOffsetMs = score.medianOffsetMs
    agentQaSyncResidualP80Ms = score.residualP80Ms
    agentQaSyncReason = score.reason
    AgentQa.writeSyncScore(score)
}
