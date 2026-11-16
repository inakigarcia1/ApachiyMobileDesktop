package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.embedded.EmbeddedSubtitleCue
import com.nuvio.app.features.player.embedded.EmbeddedTextCodec
import com.nuvio.app.features.player.embedded.EmbeddedTextTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every Spanish OpenSubtitles file for The Mentalist S2E10-12 (timings only) against the embedded
 * English reference timings captured from the desktop trace.
 */
class MentalistCandidateSurveyTest {
    private val data: Map<String, List<Pair<Long, Long>>> by lazy {
        javaClass.getResourceAsStream("/autosync/mentalist-s2-timings.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val (name, cues) = line.split('\t', limit = 2)
                name to cues.split(',').map { c -> c.split('-').let { it[0].toLong() to it[1].toLong() } }
            }
    }

    private fun track(ep: String) = EmbeddedTextTrack(
        language = null, name = null, forced = false, codec = EmbeddedTextCodec.SubRip,
        cues = data.getValue("$ep-ref").map { EmbeddedSubtitleCue(it.first, it.second, "") }, trackNumber = 3L,
    )

    private fun target(name: String) = data.getValue(name).map { SubtitleSyncCue(it.first, it.second, "") }

    private fun candidates(ep: String) = data.keys.filter { it.startsWith("$ep-") && it != "$ep-ref" }

    /** Same ranking CommunityAutoSyncRetime applies, without the network. */
    private fun choose(ep: String): Pair<String, AutoSyncTimelineRetimeResult>? {
        val refStarts = track(ep).cues.map { it.startMs }
        return candidates(ep)
            .sortedByDescending { AutoSyncOnsetAgreement.score(refStarts, target(it).map { c -> c.startTimeMs }) }
            .firstNotNullOfOrNull { name -> AutoSyncSelectedRun.retime(listOf(track(ep)), target(name))?.let { name to it } }
    }

    @Test
    fun picksTheBestFittingFilePerEpisode() {
        assertEquals("e10-1953027859", choose("e10")?.first)
        assertEquals("e11-1953650476", choose("e11")?.first)
        assertEquals("e12-1953650477", choose("e12")?.first)
    }

    @Test
    fun rejectsFilesCutForAnotherEdit() {
        // Onset agreement ~0.29-0.36: the DP can force them, but whole acts end up seconds off.
        for (name in listOf("e11-1953123486", "e11-1954098737", "e12-1952267970", "e12-1954098738")) {
            assertNull(AutoSyncSelectedRun.retime(listOf(track(name.take(3))), target(name)), name)
        }
    }

    @Test
    fun rejectsConstantDelayOnSteppedFiles() {
        for (name in listOf("e10-1957386869", "e11-1957386870", "e12-1957386871")) {
            assertNull(AutoSyncSelectedRun.retime(listOf(track(name.take(3))), target(name)), name)
        }
    }

    @Test
    fun chosenRetimeLandsOnTheReferenceEverywhere() {
        for (ep in listOf("e10", "e11", "e12")) {
            val (_, result) = choose(ep)!!
            val rates = AutoSyncOnsetAgreement.windowHitRates(track(ep).cues.map { it.startMs }, result.cues.map { it.startTimeMs })
            assertTrue(rates.min() >= 0.70, "$ep windows=$rates")
        }
    }
}
