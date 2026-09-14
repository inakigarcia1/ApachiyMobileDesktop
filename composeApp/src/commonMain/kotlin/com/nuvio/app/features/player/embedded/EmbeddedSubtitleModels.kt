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
)

internal data class MkvCueRef(
    val timeTicks: Long,
    val trackNumber: Long,
    val clusterPosition: Long,
    val relativePosition: Long?,
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
    const val PIPELINE_WAIT_MS = 45_000L

    fun shouldBindPlayer(
        pipelineDone: Boolean,
        waitExpired: Boolean,
    ): Boolean = pipelineDone || waitExpired

    fun shouldDismissOpeningOverlay(
        playerIsLoading: Boolean,
        pipelineDone: Boolean,
        playerBound: Boolean,
        waitExpired: Boolean,
    ): Boolean {
        if (!shouldBindPlayer(pipelineDone, waitExpired)) return false
        if (!playerBound || playerIsLoading) return false
        return true
    }
}
