package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerNextEpisodeRulesTest {

    @Test
    fun withoutCreditsTheCardOpensAt90PercentAndSourcesPreload15SecondsEarlier() {
        val durationMs = 40 * 60_000L
        val promptAt = PlayerNextEpisodeRules.nextEpisodePromptPositionMs(
            durationMs = durationMs,
            skipIntervals = emptyList(),
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = PlayerNextEpisodeRules.THRESHOLD_PERCENT_DEFAULT,
            thresholdMinutesBeforeEnd = 2f,
        )
        assertEquals(36 * 60_000L, promptAt)
        assertFalse(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = promptAt!! - 1,
                durationMs = durationMs,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 90f,
                thresholdMinutesBeforeEnd = 2f,
            )
        )
        assertTrue(
            PlayerNextEpisodeRules.shouldPreloadNextEpisodeSources(
                positionMs = promptAt - PlayerNextEpisodeRules.PRELOAD_LEAD_MS,
                durationMs = durationMs,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 90f,
                thresholdMinutesBeforeEnd = 2f,
            )
        )
        assertFalse(
            PlayerNextEpisodeRules.shouldPreloadNextEpisodeSources(
                positionMs = promptAt - PlayerNextEpisodeRules.PRELOAD_LEAD_MS - 1,
                durationMs = durationMs,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 90f,
                thresholdMinutesBeforeEnd = 2f,
            )
        )
    }

    @Test
    fun creditsThatFinishNearTheEndOpenTheCardWhenTheyStart() {
        val durationMs = 40 * 60_000L
        val credits = listOf(SkipInterval(37 * 60.0, 39 * 60.0 + 50, "outro", "introdb"))
        val promptAt = PlayerNextEpisodeRules.nextEpisodePromptPositionMs(
            durationMs = durationMs,
            skipIntervals = credits,
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = 90f,
            thresholdMinutesBeforeEnd = 2f,
        )
        assertEquals(37 * 60_000L, promptAt)
    }

    @Test
    fun aLongSceneAfterTheCreditsWaitsForThePercentage() {
        val durationMs = 40 * 60_000L
        val credits = listOf(SkipInterval(30 * 60.0, 32 * 60.0, "ed", "aniskip"))
        val promptAt = PlayerNextEpisodeRules.nextEpisodePromptPositionMs(
            durationMs = durationMs,
            skipIntervals = credits,
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = 90f,
            thresholdMinutesBeforeEnd = 2f,
        )
        assertEquals(36 * 60_000L, promptAt)
    }
}
