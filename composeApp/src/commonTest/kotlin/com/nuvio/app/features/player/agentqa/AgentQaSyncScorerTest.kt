package com.nuvio.app.features.player.agentqa

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentQaSyncScorerTest {
    @Test
    fun alignedShiftPasses() {
        val reference = cues(count = 40, start = 10_000L, step = 15_000L)
        val applied = cues(count = 40, start = 12_400L, step = 15_000L)
        val score = AgentQaSyncScorer.score(reference, applied)
        assertTrue(score.pass)
        assertEquals(2_400L, score.medianOffsetMs)
        assertTrue((score.residualP80Ms ?: Long.MAX_VALUE) <= 2_000L)
        assertEquals("aligned", score.reason)
    }

    @Test
    fun denserAppliedCuesWithGlobalShiftStillPass() {
        val reference = cues(count = 40, start = 10_000L, step = 15_000L)
        val applied = cues(count = 120, start = 12_400L, step = 5_000L)
        val score = AgentQaSyncScorer.score(reference, applied)
        assertTrue(score.pass)
        assertEquals(2_400L, score.medianOffsetMs)
        assertTrue((score.residualP80Ms ?: Long.MAX_VALUE) <= 2_000L)
        assertEquals("aligned", score.reason)
    }

    @Test
    fun driftingShiftFails() {
        val reference = cues(count = 24, start = 0L, step = 10_000L)
        val applied = List(24) { index ->
            SubtitleSyncCue(
                startTimeMs = index * 10_000L + index * 3_000L,
                endTimeMs = index * 10_000L + index * 3_000L + 2_000L,
                text = "line $index",
            )
        }
        val score = AgentQaSyncScorer.score(reference, applied)
        assertFalse(score.pass)
        assertEquals("residual_too_high", score.reason)
    }

    @Test
    fun tooFewAppliedCuesFail() {
        val score = AgentQaSyncScorer.score(
            reference = cues(20, 0, 10_000),
            applied = cues(4, 0, 10_000),
        )
        assertFalse(score.pass)
        assertEquals("applied_too_few", score.reason)
    }

    @Test
    fun activeCueCoversClockAfterDelay() {
        val cues = listOf(
            SubtitleSyncCue(1_000L, 3_000L, "early"),
            SubtitleSyncCue(8_000L, 10_000L, "target"),
        )
        val hit = AgentQaSyncScorer.activeCueAt(cues, positionMs = 10_200L, delayMs = 1_500)
        assertNotNull(hit)
        assertEquals("target", hit.text)
    }

    private fun cues(count: Int, start: Long, step: Long): List<SubtitleSyncCue> =
        List(count) { index ->
            val at = start + index * step
            SubtitleSyncCue(startTimeMs = at, endTimeMs = at + 2_000L, text = "line $index")
        }
}
