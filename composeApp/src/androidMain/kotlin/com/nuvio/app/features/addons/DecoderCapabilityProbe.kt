package com.nuvio.app.features.addons

import android.content.Context
import android.content.res.Configuration
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

internal data class DecoderProbe(
    val platform: String,
    val cells: Map<String, Map<String, Boolean>>,
    val video: Map<String, VideoCodecCapabilityDto>,
    val hdr: HdrCapabilityDto,
    val audio: Map<String, Boolean>,
    val codecs: List<String>,
)

private data class ProbeCell(val key: String, val width: Int, val height: Int, val fps: Int)

private val PROBE_CELLS = listOf(
    ProbeCell("720p30", 1280, 720, 30),
    ProbeCell("720p60", 1280, 720, 60),
    ProbeCell("1080p30", 1920, 1080, 30),
    ProbeCell("1080p60", 1920, 1080, 60),
    ProbeCell("1440p30", 2560, 1440, 30),
    ProbeCell("1440p60", 2560, 1440, 60),
    ProbeCell("2160p24", 3840, 2160, 24),
    ProbeCell("2160p30", 3840, 2160, 30),
    ProbeCell("2160p60", 3840, 2160, 60),
)

private data class ProfileHit(val name: String, val bitDepth: Int, val hdr: String? = null)

@OptIn(UnstableApi::class)
internal fun probeDeviceDecoders(context: Context): DecoderProbe {
    val cells = linkedMapOf<String, MutableMap<String, Boolean>>()
    val video = linkedMapOf<String, VideoCodecCapabilityDto>()
    val hdrTags = mutableSetOf<String>()
    var dolbyDecoder = false

    for ((mime, wire, shortName) in VIDEO_MIMES) {
        val infos = runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false) }.getOrNull() ?: continue
        val profiles = mutableSetOf<String>()
        val bitDepths = mutableSetOf<Int>()
        val levels = mutableSetOf<String>()
        val codecHdr = mutableSetOf<String>()
        var maxWidth = 0
        var maxHeight = 0
        val cellHits = cells.getOrPut(wire) { mutableMapOf() }
        for (decoder in infos) {
            if (!isHardwareDecoder(decoder.name, decoder.hardwareAccelerated, decoder.softwareOnly)) continue
            val caps = decoder.capabilities ?: continue
            val videoCaps = caps.videoCapabilities
            if (videoCaps != null) {
                maxWidth = maxOf(maxWidth, runCatching { videoCaps.supportedWidths.upper }.getOrDefault(0))
                maxHeight = maxOf(maxHeight, runCatching { videoCaps.supportedHeights.upper }.getOrDefault(0))
                for (cell in PROBE_CELLS) {
                    if (cellHits[cell.key] == true) continue
                    if (supportsSizeAndRate(videoCaps, cell.width, cell.height, cell.fps)) {
                        cellHits[cell.key] = true
                    }
                }
            }
            for (level in caps.profileLevels) {
                val hit = profileHit(mime, level.profile) ?: continue
                profiles += hit.name
                bitDepths += hit.bitDepth
                hit.hdr?.let {
                    codecHdr += it
                    hdrTags += it
                }
                levelLabel(mime, level.level)?.let { levels += it }
            }
        }
        if (cellHits.isEmpty()) cells.remove(wire)
        if (profiles.isEmpty() && cellHits.isEmpty()) continue
        video[shortName] = VideoCodecCapabilityDto(
            profiles = profiles.sorted(),
            maxLevel = levels.maxByOrNull { it.toFloatOrNull() ?: 0f },
            bitDepths = bitDepths.sorted(),
            maxWidth = maxWidth.takeIf { it > 0 },
            maxHeight = maxHeight.takeIf { it > 0 },
            hdr = codecHdr.sorted(),
        )
    }

    val dolbyInfos = runCatching {
        MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false)
    }.getOrNull().orEmpty()
    dolbyDecoder = dolbyInfos.any {
        isHardwareDecoder(it.name, it.hardwareAccelerated, it.softwareOnly)
    }

    val displayHdr = readDisplayHdr(context)
    hdrTags += displayHdr
    val hdr = HdrCapabilityDto(
        hdr10 = "hdr10" in hdrTags,
        hdr10Plus = "hdr10+" in hdrTags,
        dolbyVision = dolbyDecoder || "dv" in displayHdr,
        hlg = "hlg" in hdrTags,
        probed = true,
    )
    val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    val platform = if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) "android-tv" else "android"
    return DecoderProbe(
        platform = platform,
        cells = cells.mapValues { it.value.toMap() },
        video = video,
        hdr = hdr,
        audio = probeAudioDecoders(),
        codecs = cells.keys.toList(),
    )
}

private fun isHardwareDecoder(name: String, hardwareAccelerated: Boolean, softwareOnly: Boolean): Boolean {
    if (name.startsWith("c2.android.", ignoreCase = true) || name.startsWith("OMX.google.", ignoreCase = true)) {
        return false
    }
    return hardwareAccelerated && !softwareOnly
}

private fun supportsSizeAndRate(
    videoCaps: MediaCodecInfo.VideoCapabilities,
    width: Int,
    height: Int,
    fps: Int,
): Boolean {
    if (!videoCaps.isSizeSupported(width, height)) return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        videoCaps.areSizeAndRateSupported(width, height, fps.toDouble())
    } else {
        true
    }
}

private fun profileHit(mime: String, profile: Int): ProfileHit? = when (mime) {
    MimeTypes.VIDEO_H264 -> when (profile) {
        CodecProfileLevel.AVCProfileBaseline,
        CodecProfileLevel.AVCProfileConstrainedBaseline -> ProfileHit("Baseline", 8)
        CodecProfileLevel.AVCProfileMain -> ProfileHit("Main", 8)
        CodecProfileLevel.AVCProfileHigh,
        CodecProfileLevel.AVCProfileConstrainedHigh -> ProfileHit("High", 8)
        CodecProfileLevel.AVCProfileHigh10 -> ProfileHit("High10", 10)
        else -> null
    }
    MimeTypes.VIDEO_H265 -> when (profile) {
        CodecProfileLevel.HEVCProfileMain,
        CodecProfileLevel.HEVCProfileMainStill -> ProfileHit("Main", 8)
        CodecProfileLevel.HEVCProfileMain10 -> ProfileHit("Main10", 10)
        CodecProfileLevel.HEVCProfileMain10HDR10 -> ProfileHit("Main10", 10, "hdr10")
        CodecProfileLevel.HEVCProfileMain10HDR10Plus -> ProfileHit("Main10", 10, "hdr10+")
        else -> null
    }
    MimeTypes.VIDEO_VP9 -> when (profile) {
        CodecProfileLevel.VP9Profile0 -> ProfileHit("Profile0", 8)
        CodecProfileLevel.VP9Profile2 -> ProfileHit("Profile2", 10)
        CodecProfileLevel.VP9Profile2HDR -> ProfileHit("Profile2", 10, "hdr10")
        CodecProfileLevel.VP9Profile2HDR10Plus -> ProfileHit("Profile2", 10, "hdr10+")
        CodecProfileLevel.VP9Profile3,
        CodecProfileLevel.VP9Profile3HDR -> ProfileHit("Profile3", 10, "hdr10")
        else -> null
    }
    MimeTypes.VIDEO_AV1 -> when (profile) {
        CodecProfileLevel.AV1ProfileMain8 -> ProfileHit("Main", 8)
        CodecProfileLevel.AV1ProfileMain10 -> ProfileHit("Main10", 10)
        CodecProfileLevel.AV1ProfileMain10HDR10 -> ProfileHit("Main10", 10, "hdr10")
        CodecProfileLevel.AV1ProfileMain10HDR10Plus -> ProfileHit("Main10", 10, "hdr10+")
        else -> null
    }
    else -> null
}

private fun levelLabel(mime: String, level: Int): String? = when (mime) {
    MimeTypes.VIDEO_H264 -> AVC_LEVELS[level]
    MimeTypes.VIDEO_H265 -> HEVC_LEVELS[level]
    else -> null
}

private val AVC_LEVELS = mapOf(
    CodecProfileLevel.AVCLevel1 to "1",
    CodecProfileLevel.AVCLevel1b to "1.1",
    CodecProfileLevel.AVCLevel11 to "1.1",
    CodecProfileLevel.AVCLevel12 to "1.2",
    CodecProfileLevel.AVCLevel13 to "1.3",
    CodecProfileLevel.AVCLevel2 to "2",
    CodecProfileLevel.AVCLevel21 to "2.1",
    CodecProfileLevel.AVCLevel22 to "2.2",
    CodecProfileLevel.AVCLevel3 to "3",
    CodecProfileLevel.AVCLevel31 to "3.1",
    CodecProfileLevel.AVCLevel32 to "3.2",
    CodecProfileLevel.AVCLevel4 to "4",
    CodecProfileLevel.AVCLevel41 to "4.1",
    CodecProfileLevel.AVCLevel42 to "4.2",
    CodecProfileLevel.AVCLevel5 to "5",
    CodecProfileLevel.AVCLevel51 to "5.1",
    CodecProfileLevel.AVCLevel52 to "5.2",
    CodecProfileLevel.AVCLevel6 to "6",
    CodecProfileLevel.AVCLevel61 to "6.1",
    CodecProfileLevel.AVCLevel62 to "6.2",
)

private val HEVC_LEVELS = mapOf(
    CodecProfileLevel.HEVCMainTierLevel1 to "1",
    CodecProfileLevel.HEVCHighTierLevel1 to "1",
    CodecProfileLevel.HEVCMainTierLevel2 to "2",
    CodecProfileLevel.HEVCHighTierLevel2 to "2",
    CodecProfileLevel.HEVCMainTierLevel21 to "2.1",
    CodecProfileLevel.HEVCHighTierLevel21 to "2.1",
    CodecProfileLevel.HEVCMainTierLevel3 to "3",
    CodecProfileLevel.HEVCHighTierLevel3 to "3",
    CodecProfileLevel.HEVCMainTierLevel31 to "3.1",
    CodecProfileLevel.HEVCHighTierLevel31 to "3.1",
    CodecProfileLevel.HEVCMainTierLevel4 to "4",
    CodecProfileLevel.HEVCHighTierLevel4 to "4",
    CodecProfileLevel.HEVCMainTierLevel41 to "4.1",
    CodecProfileLevel.HEVCHighTierLevel41 to "4.1",
    CodecProfileLevel.HEVCMainTierLevel5 to "5",
    CodecProfileLevel.HEVCHighTierLevel5 to "5",
    CodecProfileLevel.HEVCMainTierLevel51 to "5.1",
    CodecProfileLevel.HEVCHighTierLevel51 to "5.1",
    CodecProfileLevel.HEVCMainTierLevel52 to "5.2",
    CodecProfileLevel.HEVCHighTierLevel52 to "5.2",
    CodecProfileLevel.HEVCMainTierLevel6 to "6",
    CodecProfileLevel.HEVCHighTierLevel6 to "6",
    CodecProfileLevel.HEVCMainTierLevel61 to "6.1",
    CodecProfileLevel.HEVCHighTierLevel61 to "6.1",
    CodecProfileLevel.HEVCMainTierLevel62 to "6.2",
    CodecProfileLevel.HEVCHighTierLevel62 to "6.2",
)

private val VIDEO_MIMES = listOf(
    Triple(MimeTypes.VIDEO_H264, "video/avc", "avc"),
    Triple(MimeTypes.VIDEO_H265, "video/hevc", "hevc"),
    Triple(MimeTypes.VIDEO_VP9, "video/x-vnd.on2.vp9", "vp9"),
    Triple(MimeTypes.VIDEO_AV1, "video/av01", "av1"),
)

private val AUDIO_MIMES = listOf(
    "aac" to "audio/mp4a-latm",
    "ac3" to "audio/ac3",
    "eac3" to "audio/eac3",
    "atmos" to "audio/eac3-joc",
    "truehd" to "audio/vnd.dolby.mlp",
    "dts" to "audio/vnd.dts",
    "dtshd" to "audio/vnd.dts.hd",
    "opus" to "audio/opus",
    "flac" to "audio/flac",
    "vorbis" to "audio/vorbis",
    "mp3" to "audio/mpeg",
)

private fun probeAudioDecoders(): Map<String, Boolean> {
    val types = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { !it.isEncoder }
            .flatMap { it.supportedTypes.asSequence() }
            .map { it.lowercase() }
            .toSet()
    }.getOrDefault(emptySet())
    return AUDIO_MIMES.associate { (name, mime) -> name to types.contains(mime) }
}

private fun readDisplayHdr(context: Context): Set<String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptySet()
    val display = runCatching {
        val wm = context.getSystemService(WindowManager::class.java) ?: return emptySet()
        @Suppress("DEPRECATION")
        wm.defaultDisplay
    }.getOrNull() ?: return emptySet()
    val types = display.hdrCapabilities?.supportedHdrTypes ?: return emptySet()
    val tags = mutableSetOf<String>()
    for (type in types) {
        when (type) {
            Display.HdrCapabilities.HDR_TYPE_HDR10 -> tags += "hdr10"
            Display.HdrCapabilities.HDR_TYPE_HLG -> tags += "hlg"
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> tags += "dv"
            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> tags += "hdr10+"
        }
    }
    return tags
}
