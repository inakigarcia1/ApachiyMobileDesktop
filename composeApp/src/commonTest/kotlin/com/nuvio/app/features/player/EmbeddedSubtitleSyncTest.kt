package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.player.embedded.AddonSubtitleLoadingGate
import com.nuvio.app.features.player.embedded.EmbeddedSubtitleCue
import com.nuvio.app.features.player.embedded.EmbeddedSubtitleReference
import com.nuvio.app.features.player.embedded.EmbeddedTextCodec
import com.nuvio.app.features.player.embedded.EmbeddedTextTrack
import com.nuvio.app.features.player.embedded.isApachiySubtitleAddon
import com.nuvio.app.features.player.embedded.isEmbeddedEnglishLanguage
import com.nuvio.app.features.player.embedded.isUsableEmbeddedReference
import com.nuvio.app.features.player.embedded.parseContentDispositionFilename
import com.nuvio.app.features.player.embedded.parseHttpSizeBytes
import com.nuvio.app.features.player.embedded.pickEvenIndices
import com.nuvio.app.features.player.embedded.selectEmbeddedReferenceTrack
import com.nuvio.app.features.player.embedded.MkvTextSubtitleParser
import com.nuvio.app.features.player.embedded.mkvBoundedEnd
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
    fun rankingPicksFirstNonSpanishSrtTrack() {
        val spanish = track("spa", "Spanish", cues = 12)
        val emptyRussian = track("rus", "Russian empty", cues = 0)
        val assEnglish = track("eng", "English ASS", cues = 80, codec = EmbeddedTextCodec.Ass)
        val russian = track("rus", "Russian", cues = 1)
        val french = track("fra", "French", cues = 40)
        val chosen = selectEmbeddedReferenceTrack(listOf(spanish, emptyRussian, assEnglish, russian, french))
        assertEquals("Russian", chosen?.name)
        assertEquals("rus", chosen?.language)
        assertEquals(EmbeddedTextCodec.SubRip, chosen?.codec)
        assertNull(selectEmbeddedReferenceTrack(listOf(spanish, assEnglish)))
    }

    @Test
    fun usableReferenceRequiresEnoughCuesAndSpan() {
        val tooFew = track("rus", "Russian", cues = 23)
        val shortSpan = EmbeddedTextTrack(
            language = "rus",
            name = "Russian",
            forced = false,
            codec = EmbeddedTextCodec.SubRip,
            cues = List(24) { index ->
                EmbeddedSubtitleCue(
                    startMs = index * 1_000L,
                    endMs = index * 1_000L + 800L,
                    text = "line $index",
                )
            },
        )
        val usable = EmbeddedTextTrack(
            language = "rus",
            name = "Russian",
            forced = false,
            codec = EmbeddedTextCodec.SubRip,
            cues = List(24) { index ->
                EmbeddedSubtitleCue(
                    startMs = index * 15_000L,
                    endMs = index * 15_000L + 800L,
                    text = "line $index",
                )
            },
        )
        assertFalse(isUsableEmbeddedReference(tooFew))
        assertFalse(isUsableEmbeddedReference(shortSpan))
        assertTrue(isUsableEmbeddedReference(usable))
    }

    @Test
    fun harvestBlockWindowFindsSubtitleBlockPastClusterHeader() {
        val payload = "Privet".encodeToByteArray()
        val contentSize = 1 + 2 + 1 + payload.size
        val window = ByteArray(28 + 2 + contentSize)
        var offset = 28
        window[offset++] = 0xA3.toByte()
        window[offset++] = (0x80 or contentSize).toByte()
        window[offset++] = 0x82.toByte()
        window[offset++] = 0
        window[offset++] = 0
        window[offset++] = 0
        payload.copyInto(window, offset)
        val existing = listOf(
            EmbeddedTextTrack(
                language = "rus",
                name = "Russian",
                forced = false,
                codec = EmbeddedTextCodec.SubRip,
                cues = emptyList(),
                trackNumber = 2L,
            ),
        )
        val harvested = MkvTextSubtitleParser.harvestBlockWindow(
            window,
            existing,
            timestampScale = 1_000_000L,
            cueTimeTicks = 12_345L,
        )
        val cue = harvested.single().cues.single()
        assertEquals("Privet", cue.text)
        assertEquals(12_345L, cue.startMs)
    }

    @Test
    fun pickEvenIndicesSpreadsAcrossRange() {
        assertEquals(listOf(0, 1, 2), pickEvenIndices(3, 16))
        assertEquals(listOf(0, 5, 10, 15), pickEvenIndices(16, 4))
        assertEquals(emptyList(), pickEvenIndices(0, 8))
    }

    @Test
    fun contentDispositionPrefersRfc5987EpisodeFilename() {
        val header = """attachment; filename="Smallville S01 pack.mkv"; filename*=UTF-8''Smallville.S01E07.Craving.Rus.Eng.BDRemux.mkv"""
        assertEquals(
            "Smallville.S01E07.Craving.Rus.Eng.BDRemux.mkv",
            parseContentDispositionFilename(header),
        )
    }

    @Test
    fun httpSizeBytesReadsContentRangeTotal() {
        val size = parseHttpSizeBytes(mapOf("content-range" to "bytes 0-0/7026220887"))
        assertEquals(7_026_220_887L, size)
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
    fun mkvBoundedEndDoesNotOverflowLargeSegmentSizes() {
        val prefix = 16 * 1024 * 1024
        val remuxSegmentBytes = 7_935_783_985L
        assertEquals(prefix, mkvBoundedEnd(100, remuxSegmentBytes, prefix))
        assertEquals(150, mkvBoundedEnd(100, 50L, prefix))
        assertEquals(prefix, mkvBoundedEnd(prefix, 50L, prefix))
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
        assertTrue(AddonSubtitleRequest.listingHasSubtitleUrls("""{"subtitles":[{"id":"a","url":"http://x/y"}]}"""))
        assertFalse(AddonSubtitleRequest.listingHasSubtitleUrls("""{"subtitles":[]}"""))
        assertFalse(AddonSubtitleRequest.listingHasSubtitleUrls("CSRF token is missing"))
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
    fun overlayStaysUntilPipelineAndPlayerAreReady() {
        assertFalse(
            AddonSubtitleLoadingGate.shouldBindPlayer(pipelineDone = false, waitExpired = false),
        )
        assertTrue(
            AddonSubtitleLoadingGate.shouldBindPlayer(pipelineDone = true, waitExpired = false),
        )
        assertTrue(
            AddonSubtitleLoadingGate.shouldBindPlayer(pipelineDone = false, waitExpired = true),
        )
        assertFalse(
            AddonSubtitleLoadingGate.shouldDismissOpeningOverlay(
                playerIsLoading = false,
                pipelineDone = false,
                playerBound = false,
                waitExpired = false,
            ),
        )
        assertFalse(
            AddonSubtitleLoadingGate.shouldDismissOpeningOverlay(
                playerIsLoading = false,
                pipelineDone = true,
                playerBound = false,
                waitExpired = false,
            ),
        )
        assertFalse(
            AddonSubtitleLoadingGate.shouldDismissOpeningOverlay(
                playerIsLoading = true,
                pipelineDone = true,
                playerBound = true,
                waitExpired = false,
            ),
        )
        assertTrue(
            AddonSubtitleLoadingGate.shouldDismissOpeningOverlay(
                playerIsLoading = false,
                pipelineDone = true,
                playerBound = true,
                waitExpired = false,
            ),
        )
        assertTrue(
            AddonSubtitleLoadingGate.shouldDismissOpeningOverlay(
                playerIsLoading = false,
                pipelineDone = false,
                playerBound = true,
                waitExpired = true,
            ),
        )
    }

    private fun track(
        language: String?,
        name: String?,
        forced: Boolean = false,
        cues: Int,
        codec: EmbeddedTextCodec = EmbeddedTextCodec.SubRip,
    ): EmbeddedTextTrack = EmbeddedTextTrack(
        language = language,
        name = name,
        forced = forced,
        codec = codec,
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
