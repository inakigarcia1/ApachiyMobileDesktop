package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerNextEpisodeRulesTest {

    @Test
    fun withoutCreditsA40MinuteSeriesOpens30SecondsBeforeTheEnd() {
        val durationMs = 40 * 60_000L
        val promptAt = PlayerNextEpisodeRules.nextEpisodePromptPositionMs(
            durationMs = durationMs,
            skipIntervals = emptyList(),
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = PlayerNextEpisodeRules.THRESHOLD_PERCENT_DEFAULT,
            thresholdMinutesBeforeEnd = 2f,
            useAdaptiveLead = true,
        )
        assertEquals(2_370_000L, promptAt)
        assertFalse(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = promptAt!! - 1,
                durationMs = durationMs,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = PlayerNextEpisodeRules.THRESHOLD_PERCENT_DEFAULT,
                thresholdMinutesBeforeEnd = 2f,
                useAdaptiveLead = true,
            )
        )
        assertTrue(
            PlayerNextEpisodeRules.shouldPreloadNextEpisodeSources(
                positionMs = promptAt - PlayerNextEpisodeRules.PRELOAD_LEAD_MS,
                durationMs = durationMs,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = PlayerNextEpisodeRules.THRESHOLD_PERCENT_DEFAULT,
                thresholdMinutesBeforeEnd = 2f,
                useAdaptiveLead = true,
            )
        )
        assertFalse(
            PlayerNextEpisodeRules.shouldPreloadNextEpisodeSources(
                positionMs = promptAt - PlayerNextEpisodeRules.PRELOAD_LEAD_MS - 1,
                durationMs = durationMs,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = PlayerNextEpisodeRules.THRESHOLD_PERCENT_DEFAULT,
                thresholdMinutesBeforeEnd = 2f,
                useAdaptiveLead = true,
            )
        )
    }

    @Test
    fun withoutCreditsA40MinuteAnimeOpens75SecondsBeforeTheEnd() {
        val promptAt = PlayerNextEpisodeRules.nextEpisodePromptPositionMs(
            durationMs = 40 * 60_000L,
            skipIntervals = emptyList(),
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = 96f,
            thresholdMinutesBeforeEnd = 2f,
            useAdaptiveLead = true,
            anime = true,
        )
        assertEquals(40 * 60_000L - 75_000L, promptAt)
    }

    @Test
    fun twoHoursOrLongerOpens150SecondsBeforeTheEnd() {
        val promptAt = PlayerNextEpisodeRules.nextEpisodePromptPositionMs(
            durationMs = 150 * 60_000L,
            skipIntervals = emptyList(),
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = 96f,
            thresholdMinutesBeforeEnd = 2f,
            useAdaptiveLead = true,
            anime = true,
        )
        assertEquals(150 * 60_000L - 150_000L, promptAt)
    }

    @Test
    fun aSavedPercentageStillUsesThatPercentage() {
        val promptAt = PlayerNextEpisodeRules.nextEpisodePromptPositionMs(
            durationMs = 40 * 60_000L,
            skipIntervals = emptyList(),
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = 96f,
            thresholdMinutesBeforeEnd = 2f,
        )
        assertEquals(2_304_000L, promptAt)
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
