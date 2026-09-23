package com.nuvio.app.features.autosync

import kotlin.math.max

/**
 * Near-identical embedded tracks (CHS / CHT / bilingual copies of one source) count as one
 * reference source. Copies of a single unsuitable track must not end a search on their own.
 */
internal object AutoSyncReferenceConsistency {
    private const val SAME_SOURCE_MIN_OVERLAP = 0.90

    /** Near-identical timing, for example CHS / CHT / bilingual variants of one source. */
    internal fun isSameSource(
        first: AutoSyncTimelineRetimer.PreparedActivity,
        second: AutoSyncTimelineRetimer.PreparedActivity,
    ): Boolean = fineActivityOverlap(first, second) >= SAME_SOURCE_MIN_OVERLAP

    /** Jaccard overlap of the 100 ms activity bins at zero offset. */
    private fun fineActivityOverlap(
        first: AutoSyncTimelineRetimer.PreparedActivity,
        second: AutoSyncTimelineRetimer.PreparedActivity,
    ): Double {
        val a = first.fine.packed
        val b = second.fine.packed
        var intersection = 0
        var union = 0
        for (word in 0 until max(a.size, b.size)) {
            val left = a.getOrElse(word) { 0L }
            val right = b.getOrElse(word) { 0L }
            intersection += (left and right).countOneBits()
            union += (left or right).countOneBits()
        }
        return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }
}
