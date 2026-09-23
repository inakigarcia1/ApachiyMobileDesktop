package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.embedded.EmbeddedTextTrack
import com.nuvio.app.features.player.embedded.hasEmbeddedSpanishTextTrack

internal object AutoSyncSelectedRun {
    fun retime(
        references: List<EmbeddedTextTrack>,
        target: List<SubtitleSyncCue>,
    ): AutoSyncTimelineRetimeResult? {
        if (target.size < 4) return null
        val primary = references.filter { track ->
            !hasEmbeddedSpanishTextTrack(listOf(track)) && isFullDialogue(track) && !track.forced
        }
        val forced = references.filter { track ->
            !hasEmbeddedSpanishTextTrack(listOf(track)) && isFullDialogue(track) && track.forced
        }
        return firstConfident(primary, target) ?: firstConfident(forced, target)
    }

    private fun firstConfident(
        tracks: List<EmbeddedTextTrack>,
        target: List<SubtitleSyncCue>,
    ): AutoSyncTimelineRetimeResult? {
        var best: AutoSyncTimelineRetimeResult? = null
        for (track in tracks) {
            val estimated = if (track.estimatedCueEnds) {
                track.cues.map { it.startMs }.toSet()
            } else {
                emptySet()
            }
            val result = AutoSyncTimelineRetimer.retime(
                reference = track.cues.map { cue ->
                    SubtitleSyncCue(cue.startMs, cue.endMs, cue.text)
                },
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
                referenceEstimatedEndStartsMs = estimated,
            ) ?: continue
            if (!result.confident) continue
            val current = best
            if (current == null || result.targetCoverage > current.targetCoverage) {
                best = result
            }
            if (result.targetCoverage >= 0.99 && result.referenceCoverage >= 0.97) break
        }
        return best
    }

    private fun isFullDialogue(track: EmbeddedTextTrack): Boolean {
        if (track.cues.size < 8) return false
        val span = track.cues.last().startMs - track.cues.first().startMs
        if (span < 30_000L) return false
        val minutes = span / 60_000.0
        return minutes <= 0.0 || track.cues.size / minutes >= 2.0
    }
}
