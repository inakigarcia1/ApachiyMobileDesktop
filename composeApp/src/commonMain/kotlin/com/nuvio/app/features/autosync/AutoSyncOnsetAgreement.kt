package com.nuvio.app.features.autosync

/**
 * How well a candidate subtitle's line starts line up with the embedded reference, allowing each
 * sixth of the episode its own constant offset (act breaks shift some releases in steps).
 *
 * Used only to rank candidates before the real matcher runs: a file cut for another edit or
 * re-segmented by its translator scores ~0.30, a file made for this video scores ~0.50+.
 */
internal object AutoSyncOnsetAgreement {
    private const val WINDOWS = 6
    private const val MAX_OFFSET_MS = 30_000L
    private const val COARSE_STEP_MS = 250L
    private const val FINE_STEP_MS = 50L
    private const val HIT_TOLERANCE_MS = 200L

    fun score(referenceStartsMs: List<Long>, targetStartsMs: List<Long>): Double {
        if (referenceStartsMs.isEmpty() || targetStartsMs.size < WINDOWS) return 0.0
        val reference = referenceStartsMs.sorted().toLongArray()
        val target = targetStartsMs.sorted()
        val span = target.last() + 1
        var hits = 0
        for (w in 0 until WINDOWS) {
            val from = span * w / WINDOWS
            val to = span * (w + 1) / WINDOWS
            val window = target.filter { it in from until to }
            if (window.isEmpty()) continue
            var bestHits = 0
            var bestOffset = 0L
            var offset = -MAX_OFFSET_MS
            while (offset <= MAX_OFFSET_MS) {
                val h = hitsAt(reference, window, offset)
                if (h > bestHits) { bestHits = h; bestOffset = offset }
                offset += COARSE_STEP_MS
            }
            offset = bestOffset - COARSE_STEP_MS
            while (offset <= bestOffset + COARSE_STEP_MS) {
                bestHits = maxOf(bestHits, hitsAt(reference, window, offset))
                offset += FINE_STEP_MS
            }
            hits += bestHits
        }
        return hits.toDouble() / target.size
    }

    /**
     * Per-window share of already-retimed line starts that land on a reference start with no
     * further shift. A correct retime keeps every window near the file's [score]; a constant
     * delay forced onto a file with step offsets collapses the windows it got wrong.
     */
    fun windowHitRates(referenceStartsMs: List<Long>, retimedStartsMs: List<Long>): List<Double> {
        if (referenceStartsMs.isEmpty() || retimedStartsMs.size < WINDOWS) return emptyList()
        val reference = referenceStartsMs.sorted().toLongArray()
        val target = retimedStartsMs.sorted()
        val span = target.last() + 1
        return (0 until WINDOWS).map { w ->
            val window = target.filter { it in span * w / WINDOWS until span * (w + 1) / WINDOWS }
            if (window.isEmpty()) 0.0 else hitsAt(reference, window, 0L).toDouble() / window.size
        }
    }

    private fun hitsAt(reference: LongArray, window: List<Long>, offsetMs: Long): Int {
        var hits = 0
        for (start in window) {
            val x = start + offsetMs
            var lo = 0
            var hi = reference.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (reference[mid] < x - HIT_TOLERANCE_MS) lo = mid + 1 else hi = mid
            }
            if (lo < reference.size && reference[lo] <= x + HIT_TOLERANCE_MS) hits++
        }
        return hits
    }
}
