package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.roundToLong

internal fun renderRetimedSrt(
    original: List<SubtitleSyncCue>,
    timeline: AutoSyncTimelineRetimeResult,
): String {
    val times = retimedBounds(original, timeline)
    return buildString {
        original.forEachIndexed { index, cue ->
            val (start, end) = times[index]
            append(index + 1)
            append("\n")
            append(formatSrtTime(start))
            append(" --> ")
            append(formatSrtTime(end))
            append("\n")
            append(cue.text.trim())
            append("\n\n")
        }
    }
}

internal fun retimedBounds(
    original: List<SubtitleSyncCue>,
    timeline: AutoSyncTimelineRetimeResult,
): List<Pair<Long, Long>> {
    val direct = timeline.cues.size == original.size &&
        original.indices.all { index ->
            val cue = original[index]
            val retimed = timeline.cues[index]
            kotlin.math.abs(cue.startTimeMs - retimed.originalStartTimeMs) <= 50L &&
                kotlin.math.abs(cue.endTimeMs - retimed.originalEndTimeMs) <= 50L
        }
    if (direct) {
        return timeline.cues.map { cue ->
            val start = cue.startTimeMs.coerceAtLeast(0L)
            start to cue.endTimeMs.coerceAtLeast(start + 1L)
        }
    }
    return original.map { cue ->
        val start = affine(cue.startTimeMs, timeline).coerceAtLeast(0L)
        val end = affine(cue.endTimeMs, timeline).coerceAtLeast(start + 1L)
        start to end
    }
}

private fun affine(timeMs: Long, timeline: AutoSyncTimelineRetimeResult): Long =
    (timeMs * timeline.alignmentScale + timeline.alignmentInterceptMs).roundToLong()

internal fun formatSrtTime(timeMs: Long): String {
    val clamped = timeMs.coerceAtLeast(0L)
    val hours = clamped / 3_600_000L
    val minutes = (clamped / 60_000L) % 60L
    val seconds = (clamped / 1_000L) % 60L
    val millis = clamped % 1_000L
    return "${hours.toString().padStart(2, '0')}:" +
        "${minutes.toString().padStart(2, '0')}:" +
        "${seconds.toString().padStart(2, '0')}," +
        millis.toString().padStart(3, '0')
}
