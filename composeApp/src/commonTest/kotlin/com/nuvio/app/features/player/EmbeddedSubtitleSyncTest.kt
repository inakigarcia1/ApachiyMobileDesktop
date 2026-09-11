package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.player.embedded.AddonSubtitleLoadingGate
import com.nuvio.app.features.player.embedded.EmbeddedSubtitleCue
import com.nuvio.app.features.player.embedded.EmbeddedSubtitleReference
import com.nuvio.app.features.player.embedded.EmbeddedTextCodec
import com.nuvio.app.features.player.embedded.EmbeddedTextTrack
import com.nuvio.app.features.player.embedded.isApachiySubtitleAddon
import com.nuvio.app.features.player.embedded.isEmbeddedEnglishLanguage
import com.nuvio.app.features.player.embedded.selectEmbeddedReferenceTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedSubtitleSyncTest {

    @Test
    fun englishHelperDetectsVariants() {
        assertTrue(isEmbeddedEnglishLanguage("en"))
        assertTrue(isEmbeddedEnglishLanguage("eng"))
        assertTrue(isEmbeddedEnglishLanguage("en-US"))
        assertTrue(isEmbeddedEnglishLanguage("eng-US"))
        assertTrue(isEmbeddedEnglishLanguage(null, "English"))
        assertTrue(isEmbeddedEnglishLanguage("EN"))
        assertFalse(isEmbeddedEnglishLanguage("spa"))
        assertFalse(isEmbeddedEnglishLanguage("es-419"))
        assertFalse(isEmbeddedEnglishLanguage(null, "Español"))
    }

    @Test
    fun rankingPrefersEnglishDialogueThenCueCount() {
        val spanish = track("spa", "Spanish", cues = 12)
        val forcedEn = track("eng", "English Forced", forced = true, cues = 8)
        val sdhEn = track("eng", "English SDH", cues = 20)
        val dialogueEn = track("eng", "English", cues = 10)
        val french = track("fra", "French", cues = 40)

        val chosen = selectEmbeddedReferenceTrack(listOf(spanish, forcedEn, sdhEn, dialogueEn, french))
        assertEquals("English", chosen?.name)
        assertEquals("eng", chosen?.language)
    }

    @Test
    fun rankingFallsBackToNonSpanishWhenEnglishMissing() {
        val spanish = track("spa", "Spanish", cues = 30)
        val forcedFr = track("fra", "French Forced", forced = true, cues = 5)
        val dialogueDe = track("deu", "German", cues = 9)
        val chosen = selectEmbeddedReferenceTrack(listOf(spanish, forcedFr, dialogueDe))
        assertEquals("German", chosen?.name)
    }

    @Test
    fun rankingReturnsNullWhenOnlySpanishExists() {
        assertNull(selectEmbeddedReferenceTrack(listOf(track("es", "Español", cues = 4))))
    }

    @Test
    fun postsOnlyToApachiyAddon() {
        val apachiy = manifest("com.apachiy.addon", "https://api.example/apachiy/manifest.json")
        val other = manifest("org.stremio.subtitles", "https://subs.example/manifest.json")
        val reference = EmbeddedSubtitleReference("1\n00:00:00,000 --> 00:00:01,000\nHi\n".encodeToByteArray(), "embedded.srt", "eng")

        assertTrue(
            AddonSubtitleRequest.shouldPostEmbeddedReference(
                manifest = apachiy,
                subtitleUrl = "https://api.example/apachiy/subtitles/movie/tt1.json",
                reference = reference,
            ),
        )
        assertFalse(
            AddonSubtitleRequest.shouldPostEmbeddedReference(
                manifest = other,
                subtitleUrl = "https://subs.example/subtitles/movie/tt1.json",
                reference = reference,
            ),
        )
        assertFalse(
            AddonSubtitleRequest.shouldPostEmbeddedReference(
                manifest = apachiy,
                subtitleUrl = "https://api.example/apachiy/subtitles/movie/tt1.json",
                reference = null,
            ),
        )
        assertTrue(isApachiySubtitleAddon(apachiy, "https://api.example/apachiy/subtitles/movie/tt1.json"))
        assertFalse(isApachiySubtitleAddon(other, "https://subs.example/subtitles/movie/tt1.json"))
    }

    @Test
    fun postFallsBackToGetUnless2xx() {
        assertFalse(AddonSubtitleRequest.shouldFallbackPostToGet(200))
        assertFalse(AddonSubtitleRequest.shouldFallbackPostToGet(204))
        assertTrue(AddonSubtitleRequest.shouldFallbackPostToGet(404))
        assertTrue(AddonSubtitleRequest.shouldFallbackPostToGet(405))
        assertTrue(AddonSubtitleRequest.shouldFallbackPostToGet(415))
        assertTrue(AddonSubtitleRequest.shouldFallbackPostToGet(500))
        assertTrue(AddonSubtitleRequest.shouldFallbackPostToGet(503))
        assertTrue(AddonSubtitleRequest.shouldFallbackPostToGet(400))
    }

    @Test
    fun multipartIncludesReferenceFileAndOptionalLang() {
        val reference = EmbeddedSubtitleReference(
            bytes = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nHi\n".encodeToByteArray(),
            filename = "embedded.vtt",
            language = "eng",
        )
        val (contentType, body) = AddonSubtitleRequest.buildMultipartBody(reference)
        assertTrue(contentType.startsWith("multipart/form-data; boundary="))
        assertTrue(body.contains("name=\"reference\""))
        assertTrue(body.contains("filename=\"embedded.vtt\""))
        assertTrue(body.contains("name=\"referenceLang\""))
        assertTrue(body.contains("eng"))
        assertTrue(body.contains("WEBVTT"))
    }

    @Test
    fun overlayWaitsForPipelineUnlessTimeoutAndKeepsBuffering() {
        assertFalse(
            AddonSubtitleLoadingGate.shouldDismissOpeningOverlay(
                playerIsLoading = false,
                pipelineDone = false,
                elapsedMs = 1_000L,
            ),
        )
        assertTrue(
            AddonSubtitleLoadingGate.shouldDismissOpeningOverlay(
                playerIsLoading = false,
                pipelineDone = true,
                elapsedMs = 1_000L,
            ),
        )
        assertTrue(
            AddonSubtitleLoadingGate.shouldDismissOpeningOverlay(
                playerIsLoading = false,
                pipelineDone = false,
                elapsedMs = AddonSubtitleLoadingGate.TIMEOUT_MS,
            ),
        )
        assertFalse(
            AddonSubtitleLoadingGate.shouldDismissOpeningOverlay(
                playerIsLoading = true,
                pipelineDone = true,
                elapsedMs = AddonSubtitleLoadingGate.TIMEOUT_MS,
            ),
        )
    }

    private fun track(
        language: String?,
        name: String?,
        forced: Boolean = false,
        cues: Int,
    ): EmbeddedTextTrack = EmbeddedTextTrack(
        language = language,
        name = name,
        forced = forced,
        codec = EmbeddedTextCodec.SubRip,
        cues = List(cues) { index ->
            EmbeddedSubtitleCue(
                startMs = index * 1_000L,
                endMs = index * 1_000L + 800L,
                text = "line $index",
            )
        },
    )

    private fun manifest(id: String, transportUrl: String): AddonManifest = AddonManifest(
        id = id,
        name = id,
        description = "",
        version = "1.0.0",
        resources = emptyList(),
        types = emptyList(),
        transportUrl = transportUrl,
    )
}
