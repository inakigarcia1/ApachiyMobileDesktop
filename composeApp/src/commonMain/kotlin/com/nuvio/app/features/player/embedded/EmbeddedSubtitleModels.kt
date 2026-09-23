package com.nuvio.app.features.player.embedded

data class EmbeddedSubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class EmbeddedTextTrack(
    val language: String?,
    val name: String?,
    val forced: Boolean,
    val codec: EmbeddedTextCodec,
    val cues: List<EmbeddedSubtitleCue>,
    val assHeader: String? = null,
    val trackNumber: Long = 0L,
    val estimatedCueEnds: Boolean = false,
)

enum class EmbeddedTextCodec {
    SubRip,
    Ass,
    Ssa,
    WebVtt,
}

data class EmbeddedSubtitleReference(
    val bytes: ByteArray,
    val filename: String,
    val language: String?,
)

internal data class MkvLayout(
    val tracks: List<EmbeddedTextTrack>,
    val timestampScale: Long,
    val segmentDataOffset: Long,
    val cuesOffset: Long?,
    val tracksOffset: Long? = null,
)

internal data class MkvCueRef(
    val timeTicks: Long,
    val trackNumber: Long,
    val clusterPosition: Long,
    val relativePosition: Long?,
)

internal data class DialogueTimingIndex(
    val hasEmbeddedSpanish: Boolean = false,
    val tracks: List<EmbeddedTextTrack> = emptyList(),
    val noSubtitleTracks: Boolean = false,
)

data class EmbeddedExtractResult(
    val hasEmbeddedSpanish: Boolean,
    val reference: EmbeddedSubtitleReference?,
    val mediaReady: Boolean = true,
)

data class MediaFileIdentity(
    val filename: String? = null,
    val sizeBytes: Long? = null,
)

internal object AddonSubtitleLoadingGate {
    fun shouldBindPlayer(pipelineDone: Boolean): Boolean = pipelineDone

    fun shouldDismissOpeningOverlay(
        playerIsLoading: Boolean,
        pipelineDone: Boolean,
        playerBound: Boolean,
    ): Boolean {
        if (!pipelineDone || !playerBound || playerIsLoading) return false
        return true
    }
}
