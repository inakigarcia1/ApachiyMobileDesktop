package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.embedded.EmbeddedTextCodec
import com.nuvio.app.features.player.embedded.EmbeddedTextTrack
import com.nuvio.app.features.player.embedded.hasEmbeddedSpanishTextTrack

internal object AutoSyncSelectedRun {
    fun retime(
        references: List<EmbeddedTextTrack>,
        target: List<SubtitleSyncCue>,
    ): AutoSyncTimelineRetimeResult? {
        if (target.size < 4) {
            AutoSyncTrace.log("matcher: target too small cues=${target.size}")
            return null
        }
        val usable = if (references.any { it.codec != EmbeddedTextCodec.Pgs }) {
            references.filter { it.codec != EmbeddedTextCodec.Pgs }
        } else {
            references
        }
        val primary = usable.filter { track ->
            !hasEmbeddedSpanishTextTrack(listOf(track)) && isFullDialogue(track) && !track.forced
        }
        val forced = usable.filter { track ->
            !hasEmbeddedSpanishTextTrack(listOf(track)) && isFullDialogue(track) && track.forced
        }
        AutoSyncTrace.log(
            "matcher: target cues=${target.size} firstMs=${target.take(3).map { it.startTimeMs }} " +
                "refs=${references.size} usable=${usable.size} primary=${primary.size} forced=${forced.size}",
        )
        usable.filter { it !in primary && it !in forced }.forEach {
            AutoSyncTrace.log("matcher: skipped ref (spanish/not full dialogue) ${AutoSyncTrace.describe(it)}")
        }
        AutoSyncTrace.log("dump target: " + target.joinToString(",") { "${it.startTimeMs}-${it.endTimeMs}" })
        usable.forEach { track ->
            AutoSyncTrace.log("dump ref #${track.trackNumber}: " + track.cues.joinToString(",") { "${it.startMs}-${it.endMs}" })
        }
        val chosen = firstConfident(primary, target) ?: firstConfident(forced, target)
        chosen?.let { result ->
            AutoSyncTrace.log(
                "dump retimed: " + result.cues.joinToString(",") { "${it.originalStartTimeMs}>${it.startTimeMs}" },
            )
        }
        AutoSyncTrace.log("matcher: result=${chosen?.let(AutoSyncTrace::describe) ?: "REJECTED"}")
        return chosen
    }

    private fun firstConfident(
        tracks: List<EmbeddedTextTrack>,
        target: List<SubtitleSyncCue>,
    ): AutoSyncTimelineRetimeResult? {
        var best: AutoSyncTimelineRetimeResult? = null
        val targetActivity = AutoSyncTimelineRetimer.prepareUnitActivity(target)
        for (track in tracks) {
            val estimated = if (track.estimatedCueEnds) {
                track.cues.map { it.startMs }.toSet()
            } else {
                emptySet()
            }
            val reference = track.cues.map { cue -> SubtitleSyncCue(cue.startMs, cue.endMs, cue.text) }
            // Same delay-only preflight Android runs before affine discovery (AutoSyncDelayPreflight):
            // without it, a 1.001 FPS-ratio peak hides a valid constant offset behind a thin margin.
            val referenceActivity = AutoSyncTimelineRetimer.prepareUnitActivity(reference)
            val relaxDelayMargin = isSdh(track) || reference.size.toLong() * 2L >= target.size.toLong() * 3L
            val evidence = if (referenceActivity != null && targetActivity != null) {
                AutoSyncTimelineRetimer.prepareDelayOnlySearchEvidence(referenceActivity, targetActivity)
            } else {
                null
            }
            val delayHint = if (referenceActivity != null && targetActivity != null && evidence != null) {
                AutoSyncTimelineRetimer.findDelayOnlyAlignmentPrepared(
                    referenceActivity = referenceActivity,
                    targetActivity = targetActivity,
                    targetSize = target.size,
                    allowAmbiguousMargin = relaxDelayMargin,
                    precomputedEvidence = evidence,
                )
            } else {
                null
            }
            AutoSyncTrace.log(
                "matcher: preflight delay=${delayHint?.offsetMs?.toLong()} score=${delayHint?.score} " +
                    "margin=${delayHint?.margin} segments=${delayHint?.segmentsPassed} relax=$relaxDelayMargin",
            )
            val result = AutoSyncTimelineRetimer.retime(
                reference = reference,
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
                allowAmbiguousDelayOnlyMargin = relaxDelayMargin,
                referenceEstimatedEndStartsMs = estimated,
                preparedReferenceActivity = referenceActivity,
                preparedTargetActivity = targetActivity,
                precomputedDelayOnly = delayHint,
                precomputedDelayOnlyEvidence = evidence,
                allowPrecomputedDelayFastPath = delayHint != null,
            )
            AutoSyncTrace.log(
                "matcher: ref ${AutoSyncTrace.describe(track)} -> ${result?.let(AutoSyncTrace::describe) ?: "null"}",
            )
            if (result == null || !result.confident) continue
            if (track.codec != EmbeddedTextCodec.Pgs && !landsOnReference(track, result)) continue
            val current = best
            if (current == null || result.targetCoverage > current.targetCoverage) {
                best = result
            }
            if (result.targetCoverage >= 0.99 && result.referenceCoverage >= 0.97) break
        }
        return best
    }

    /**
     * Final check on the retimed output: its line starts must land on the reference in every sixth
     * of the episode. Catches a constant delay forced onto a file whose offset steps at act breaks
     * (worst window 0.05-0.24) and a file cut for another edit (mean ~0.72); correct retimes measured
     * worst >= 0.72, mean >= 0.80 on The Mentalist S2E10-12. Text references only: PGS starts are
     * display events, not line starts.
     */
    private fun landsOnReference(track: EmbeddedTextTrack, result: AutoSyncTimelineRetimeResult): Boolean {
        val rates = AutoSyncOnsetAgreement.windowHitRates(
            referenceStartsMs = track.cues.map { it.startMs },
            retimedStartsMs = result.cues.map { it.startTimeMs },
        )
        if (rates.isEmpty()) return true
        val ok = rates.min() >= VERIFY_MIN_WINDOW_HIT_RATE && rates.average() >= VERIFY_MEAN_HIT_RATE
        AutoSyncTrace.log(
            "matcher: verify ${if (ok) "pass" else "FAIL"} windows=${rates.map { kotlin.math.round(it * 100).toInt() }}",
        )
        return ok
    }

    private const val VERIFY_MIN_WINDOW_HIT_RATE = 0.60
    private const val VERIFY_MEAN_HIT_RATE = 0.75

    /** Name-based half of Android's isSdhReferenceTrack; the index carries no role flags. */
    private fun isSdh(track: EmbeddedTextTrack): Boolean {
        val label = track.name.orEmpty().lowercase()
        return listOf("sdh", "shd", "hoh", "hearing impaired", "hearing-impaired", "closed caption")
            .any { label.contains(it) }
    }

    private fun isFullDialogue(track: EmbeddedTextTrack): Boolean {
        if (track.cues.size < 8) return false
        val span = track.cues.last().startMs - track.cues.first().startMs
        if (span < 30_000L) return false
        val minutes = span / 60_000.0
        return minutes <= 0.0 || track.cues.size / minutes >= 2.0
    }
}
