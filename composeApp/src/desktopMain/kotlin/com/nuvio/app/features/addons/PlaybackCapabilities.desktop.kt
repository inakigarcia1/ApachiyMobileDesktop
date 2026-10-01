package com.nuvio.app.features.addons

import java.awt.GraphicsEnvironment
import java.util.Base64
import java.util.prefs.Preferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal actual object PlaybackCapabilitiesProvider {
    private var monitorScreen: PlaybackScreenDto? = null

    fun setMonitorSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            monitorScreen = PlaybackScreenDto(width, height)
        }
    }

    fun recordDecoderFailure(mime: String?, codecs: String?, height: Int) {
        DesktopPlaybackFailureMemory.record(mime, codecs, height)
    }

    actual fun snapshot(): PlaybackCapabilitiesPayload? {
        val screen = monitorScreen ?: readPrimaryMonitor() ?: return null
        return libmpvCapabilities(screen, DesktopPlaybackFailureMemory.snapshot())
    }

    private fun readPrimaryMonitor(): PlaybackScreenDto? {
        val device = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        }.getOrNull() ?: return null
        val mode = device.displayMode
        return PlaybackScreenDto(mode.width, mode.height).takeIf { it.width > 0 && it.height > 0 }
    }
}

private fun libmpvCapabilities(
    screen: PlaybackScreenDto,
    failures: List<ObservedPlaybackFailureDto>?,
): PlaybackCapabilitiesPayload {
    val cells = listOf("720p30", "720p60", "1080p30", "1080p60", "1440p30", "1440p60", "2160p24", "2160p30", "2160p60")
        .associateWith { true }
    fun codec(profiles: List<String>, depths: List<Int>, hdr: List<String> = emptyList()) = VideoCodecCapabilityDto(
        profiles = profiles,
        maxLevel = "6.2",
        bitDepths = depths,
        maxWidth = 7680,
        maxHeight = 4320,
        hdr = hdr,
    )
    return PlaybackCapabilitiesPayload(
        platform = "desktop",
        screen = screen,
        playerBackend = "libmpv",
        codecs = listOf("video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/av01", "video/x-vnd.on2.vp8", "video/mpeg2"),
        decoderCapabilities = mapOf(
            "video/avc" to cells,
            "video/hevc" to cells,
            "video/x-vnd.on2.vp9" to cells,
            "video/av01" to cells,
        ),
        video = mapOf(
            "avc" to codec(listOf("Baseline", "Main", "High", "High10"), listOf(8, 10)),
            "hevc" to codec(listOf("Main", "Main10"), listOf(8, 10), listOf("hdr10", "hdr10+", "hlg")),
            "vp9" to codec(listOf("Profile0", "Profile2"), listOf(8, 10), listOf("hdr10")),
            "av1" to codec(listOf("Main", "Main10"), listOf(8, 10), listOf("hdr10")),
            "vp8" to codec(listOf("Profile0"), listOf(8)),
            "mpeg2" to codec(listOf("Main"), listOf(8)),
        ),
        hdr = HdrCapabilityDto(
            hdr10 = true,
            hdr10Plus = true,
            dolbyVision = true,
            hlg = true,
            probed = true,
        ),
        audio = mapOf(
            "aac" to true,
            "ac3" to true,
            "eac3" to true,
            "atmos" to true,
            "truehd" to true,
            "dts" to true,
            "dtshd" to true,
            "dtsx" to true,
            "opus" to true,
            "flac" to true,
            "vorbis" to true,
            "mp3" to true,
        ),
        observedFailures = failures,
    )
}

private object DesktopPlaybackFailureMemory {
    private const val KEY = "failures"
    private const val MAX_ENTRIES = 8
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = Preferences.userNodeForPackage(DesktopPlaybackFailureMemory::class.java)

    fun snapshot(): List<ObservedPlaybackFailureDto>? {
        val raw = prefs.get(KEY, null) ?: return null
        return runCatching { json.decodeFromString<List<ObservedPlaybackFailureDto>>(raw) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun record(mime: String?, codecs: String?, height: Int) {
        val codecsLower = codecs?.lowercase().orEmpty()
        val codec = when {
            mime.equals("video/hevc", true) || codecsLower.startsWith("hvc1") || codecsLower.startsWith("hev1") -> "hevc"
            mime.equals("video/avc", true) || codecsLower.startsWith("avc1") -> "avc"
            mime.equals("video/av01", true) -> "av1"
            mime.equals("video/x-vnd.on2.vp9", true) -> "vp9"
            else -> return
        }
        val profile = when {
            codecsLower.startsWith("hvc1.2") || codecsLower.startsWith("hev1.2") || codecsLower.startsWith("dvh") -> "Main10"
            codecsLower.startsWith("hvc1.1") || codecsLower.startsWith("hev1.1") -> "Main"
            else -> null
        }
        val bucket = when {
            height >= 2160 -> 2160
            height >= 1080 -> 1080
            height > 0 -> height
            else -> null
        }
        val current = snapshot().orEmpty().toMutableList()
        val index = current.indexOfFirst { it.codec == codec && it.profile == profile && it.height == bucket }
        if (index >= 0) {
            current[index] = current[index].copy(count = current[index].count + 1, mime = mime, codecs = codecs)
        } else {
            current.add(ObservedPlaybackFailureDto(codec = codec, profile = profile, height = bucket, mime = mime, codecs = codecs))
        }
        prefs.put(KEY, json.encodeToString(current.takeLast(MAX_ENTRIES)))
    }
}

internal actual fun encodePlaybackQueryValue(json: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
