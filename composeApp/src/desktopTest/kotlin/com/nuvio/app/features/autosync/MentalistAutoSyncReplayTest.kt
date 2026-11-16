package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.embedded.EmbeddedSubtitleCue
import com.nuvio.app.features.player.embedded.EmbeddedTextCodec
import com.nuvio.app.features.player.embedded.EmbeddedTextTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Replays real cue dumps from desktop traces (The Mentalist S2E10/S2E11) through the common matcher. */
class MentalistAutoSyncReplayTest {
    private val dumps: Map<String, List<LongRange>> by lazy {
        val text = javaClass.getResourceAsStream("/autosync/mentalist-dumps.txt")!!.bufferedReader().readText()
        text.lines().filter { it.isNotBlank() }.associate { line ->
            val (name, data) = line.split('\t', limit = 2)
            name to data.split(',').map { pair ->
                val (s, e) = pair.trim().split('-')
                s.toLong()..e.toLong()
            }
        }
    }

    private fun reference(key: String) = EmbeddedTextTrack(
        language = null, name = null, forced = false, codec = EmbeddedTextCodec.SubRip,
        cues = dumps.getValue(key).map { EmbeddedSubtitleCue(it.first, it.last, "x") },
        trackNumber = 3L,
    )

    private fun target(key: String) =
        dumps.getValue(key).map { SubtitleSyncCue(it.first, it.last, "x") }

    private fun run(ref: String, cand: String) =
        AutoSyncSelectedRun.retime(listOf(reference(ref)), target(cand))

    @Test
    fun s2e10() {
        val ok = assertNotNull(run("e10-ref", "e10-1953027859"))
        assertTrue(kotlin.math.abs(ok.alignmentInterceptMs - 300.0) <= 100.0, "offset=${ok.alignmentInterceptMs}")
        assertNull(run("e10-ref", "e10-1952298827"))
        assertNull(run("e10-ref", "e10-1952249202"))
    }

    @Test
    fun s2e11() {
        val cands = listOf("1952284328", "1954277717", "1953650476", "1953123486", "1953184844", "community")
        val accepted = cands.mapNotNull { c -> run("e11-ref", "e11-$c")?.let { c to it.alignmentInterceptMs } }
        println("S2E11 accepted: $accepted")
        assertTrue(accepted.isNotEmpty(), "no S2E11 candidate accepted")
        assertTrue(accepted.all { it.second in 100.0..800.0 }, "accepted outlier: $accepted")
    }
}
