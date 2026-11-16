package com.nuvio.app.features.autosync

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.embedded.DialogueTimingIndex
import com.nuvio.app.features.player.embedded.EmbeddedTextTrack

/** Debug trace for the common AutoSync pipeline. Desktop debug mirrors tag [TAG] to a file. */
internal object AutoSyncTrace {
    const val TAG = "AutoSyncTrace"
    private val log = Logger.withTag(TAG)

    fun log(message: String) = log.i { message }

    fun describe(index: DialogueTimingIndex): String =
        "spanish=${index.hasEmbeddedSpanish} noSubTracks=${index.noSubtitleTracks} " +
            "tracks=[${index.tracks.joinToString("; ") { describe(it) }}]"

    fun describe(track: EmbeddedTextTrack): String {
        val span = if (track.cues.size >= 2) (track.cues.last().startMs - track.cues.first().startMs) / 1000 else 0
        return "#${track.trackNumber} lang=${track.language} name=${track.name} codec=${track.codec} " +
            "forced=${track.forced} cues=${track.cues.size} spanS=$span estEnds=${track.estimatedCueEnds} " +
            "first=${track.cues.take(3).map { it.startMs }}"
    }

    fun describe(result: AutoSyncTimelineRetimeResult): String =
        "confident=${result.confident} tCov=${r(result.targetCoverage)} " +
            "rCov=${r(result.referenceCoverage)} scale=${result.alignmentScale} " +
            "interceptMs=${result.alignmentInterceptMs.toLong()} src=${result.alignmentSource} " +
            "cost=${r(result.averageGroupCost)} skipT=${result.skippedTargetCues} " +
            "skipR=${result.skippedReferenceCues} longestSkip=${result.longestTargetSkipRun}"

    private fun r(value: Double): Double = kotlin.math.round(value * 1000.0) / 1000.0

    /** Host + last path segment only: no tokens or full stream URLs in the log. */
    fun shortUrl(url: String?): String {
        if (url.isNullOrBlank()) return "<none>"
        val noQuery = url.substringBefore('?')
        val host = noQuery.substringAfter("://", noQuery).substringBefore('/')
        return "$host/…/${noQuery.substringAfterLast('/').take(60)}"
    }
}
