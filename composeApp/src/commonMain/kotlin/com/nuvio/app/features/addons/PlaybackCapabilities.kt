package com.nuvio.app.features.addons

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PlaybackScreenDto(
    val width: Int,
    val height: Int,
)

@Serializable
data class VideoCodecCapabilityDto(
    val profiles: List<String> = emptyList(),
    val maxLevel: String? = null,
    val bitDepths: List<Int> = emptyList(),
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val hdr: List<String> = emptyList(),
)

@Serializable
data class HdrCapabilityDto(
    val hdr10: Boolean? = null,
    val hdr10Plus: Boolean? = null,
    val dolbyVision: Boolean? = null,
    val hlg: Boolean? = null,
    val probed: Boolean? = null,
)

@Serializable
data class ObservedPlaybackFailureDto(
    val codec: String? = null,
    val profile: String? = null,
    val height: Int? = null,
    val mime: String? = null,
    val codecs: String? = null,
    val dolbyVision: Boolean = false,
    val count: Int = 1,
)

@Serializable
data class PlaybackCapabilitiesPayload(
    val platform: String? = null,
    val screen: PlaybackScreenDto? = null,
    val playerBackend: String? = null,
    val codecs: List<String>? = null,
    @SerialName("decoderCapabilities")
    val decoderCapabilities: Map<String, Map<String, Boolean>>? = null,
    val video: Map<String, VideoCodecCapabilityDto>? = null,
    val hdr: HdrCapabilityDto? = null,
    val audio: Map<String, Boolean>? = null,
    val observedFailures: List<ObservedPlaybackFailureDto>? = null,
    val speedMbps: Double? = null,
    val runtimeMinutes: Int? = null,
) {
    fun hasAnyConstraint(): Boolean =
        (screen != null && screen.width > 0 && screen.height > 0)
            || !codecs.isNullOrEmpty()
            || !decoderCapabilities.isNullOrEmpty()
            || !video.isNullOrEmpty()
            || hdr != null
            || !audio.isNullOrEmpty()
            || (speedMbps != null && speedMbps > 0.0)
            || (runtimeMinutes != null && runtimeMinutes > 0)
}

internal expect object PlaybackCapabilitiesProvider {
    fun snapshot(): PlaybackCapabilitiesPayload?
}

private val playbackJson = Json {
    encodeDefaults = false
    explicitNulls = false
}

internal fun isApachiyStreamAddon(manifestUrl: String, manifestId: String = ""): Boolean {
    if (manifestId.equals("com.apachiy.addon", ignoreCase = true)) return true
    return manifestUrl.contains("/apachiy/", ignoreCase = true)
}

internal fun appendPlaybackCapabilitiesQuery(
    resourceUrl: String,
    manifestUrl: String,
    manifestId: String = "",
    runtimeMinutes: Int? = null,
): String {
    if (!isApachiyStreamAddon(manifestUrl, manifestId)) return resourceUrl
    val payload = playbackPayloadForStream(runtimeMinutes)?.takeIf { it.hasAnyConstraint() } ?: return resourceUrl
    val json = playbackJson.encodeToString(payload)
    val encoded = encodePlaybackQueryValue(json)
    val separator = if (resourceUrl.contains('?')) '&' else '?'
    return "$resourceUrl$separator" + "playback=" + encoded.encodeUnsafeHttpUrlCharacters()
}

internal expect fun encodePlaybackQueryValue(json: String): String
