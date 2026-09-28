package com.nuvio.app.features.addons

import android.content.Context
import android.media.MediaCodecInfo
import android.os.Build
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.util.Base64

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

private val MIME_WIRE_NAMES = listOf(
    MimeTypes.VIDEO_H264 to "video/avc",
    MimeTypes.VIDEO_H265 to "video/hevc",
    MimeTypes.VIDEO_VP9 to "video/x-vnd.on2.vp9",
    MimeTypes.VIDEO_AV1 to "video/av01",
)

@OptIn(UnstableApi::class)
internal actual object PlaybackCapabilitiesProvider {
    private var appContext: Context? = null
    private var cached: PlaybackCapabilitiesPayload? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun snapshot(): PlaybackCapabilitiesPayload? {
        cached?.let { return it }
        val context = appContext ?: return null
        val payload = probe(context)
        cached = payload
        return payload
    }

    @OptIn(UnstableApi::class)
    private fun probe(context: Context): PlaybackCapabilitiesPayload? {
        val screen = readPhysicalScreen(context) ?: return null
        val decoderCapabilities = buildDecoderMatrix()
        if (decoderCapabilities.isEmpty()) {
            return PlaybackCapabilitiesPayload(screen = screen)
        }
        return PlaybackCapabilitiesPayload(
            screen = screen,
            decoderCapabilities = decoderCapabilities,
        )
    }

    private fun readPhysicalScreen(context: Context): PlaybackScreenDto? {
        val wm = context.getSystemService(WindowManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            return PlaybackScreenDto(bounds.width(), bounds.height()).takeIf {
                it.width > 0 && it.height > 0
            }
        }
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay ?: return null
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return PlaybackScreenDto(metrics.widthPixels, metrics.heightPixels).takeIf {
            it.width > 0 && it.height > 0
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildDecoderMatrix(): Map<String, Map<String, Boolean>> {
        val matrix = mutableMapOf<String, MutableMap<String, Boolean>>()
        for ((mimeType, wireName) in MIME_WIRE_NAMES) {
            val decoders = MediaCodecUtil.getDecoderInfos(mimeType, false, false)
            val cells = mutableMapOf<String, Boolean>()
            for (decoder in decoders) {
                if (!decoder.hardwareAccelerated || decoder.softwareOnly) continue
                val videoCaps = decoder.capabilities?.videoCapabilities ?: continue
                for (cell in PROBE_CELLS) {
                    if (cells[cell.key] == true) continue
                    if (!supports(videoCaps, cell.width, cell.height, cell.fps)) continue
                    cells[cell.key] = true
                }
            }
            if (cells.isNotEmpty()) {
                matrix[wireName] = cells
            }
        }
        return matrix
    }

    private fun supports(
        videoCaps: MediaCodecInfo.VideoCapabilities,
        width: Int,
        height: Int,
        fps: Int,
    ): Boolean {
        if (!videoCaps.isSizeSupported(width, height)) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            videoCaps.areSizeAndRateSupported(width, height, fps.toDouble())
        } else {
            @Suppress("DEPRECATION")
            videoCaps.isSizeSupported(width, height)
        }
    }
}

internal actual fun encodePlaybackQueryValue(json: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
