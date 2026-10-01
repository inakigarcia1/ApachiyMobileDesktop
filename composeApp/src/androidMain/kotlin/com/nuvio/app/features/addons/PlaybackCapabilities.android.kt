package com.nuvio.app.features.addons

import android.content.Context
import android.os.Build
import android.view.WindowManager
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    fun recordDecoderFailure(mime: String?, codecs: String?, height: Int) {
        val context = appContext ?: return
        PlaybackFailureMemory.record(context, mime, codecs, height)
        cached = null
    }

    private fun probe(context: Context): PlaybackCapabilitiesPayload? {
        val screen = readPhysicalScreen(context) ?: return null
        val probed = runCatching { probeDeviceDecoders(context) }.getOrNull()
        val failures = PlaybackFailureMemory.snapshot(context)
        if (probed == null) {
            return PlaybackCapabilitiesPayload(
                platform = "android",
                screen = screen,
                playerBackend = "exoplayer",
                observedFailures = failures,
            )
        }
        return PlaybackCapabilitiesPayload(
            platform = probed.platform,
            screen = screen,
            playerBackend = "exoplayer",
            codecs = probed.codecs,
            decoderCapabilities = probed.cells,
            video = probed.video,
            hdr = probed.hdr,
            audio = probed.audio,
            observedFailures = failures,
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

}

internal object PlaybackFailureMemory {
    private const val PREFS = "playback_compat_failures"
    private const val KEY = "failures"
    private const val MAX_ENTRIES = 8
    private val json = Json { ignoreUnknownKeys = true }

    fun snapshot(context: Context): List<ObservedPlaybackFailureDto>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return runCatching { json.decodeFromString<List<ObservedPlaybackFailureDto>>(raw) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun record(context: Context, mime: String?, codecs: String?, height: Int) {
        val codec = failureCodec(mime, codecs) ?: return
        val profile = failureProfile(codecs)
        val dolby = codecs?.startsWith("dvh", ignoreCase = true) == true ||
            mime.equals("video/dolby-vision", ignoreCase = true)
        val bucket = when {
            height >= 2160 -> 2160
            height >= 1080 -> 1080
            height >= 720 -> 720
            height > 0 -> height
            else -> null
        }
        val current = snapshot(context).orEmpty().toMutableList()
        val index = current.indexOfFirst {
            it.codec == codec && it.profile == profile && it.height == bucket && it.dolbyVision == dolby
        }
        if (index >= 0) {
            val existing = current[index]
            current[index] = existing.copy(count = existing.count + 1, mime = mime, codecs = codecs)
        } else {
            current.add(
                ObservedPlaybackFailureDto(
                    codec = codec,
                    profile = profile,
                    height = bucket,
                    mime = mime,
                    codecs = codecs,
                    dolbyVision = dolby,
                ),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, json.encodeToString(current.takeLast(MAX_ENTRIES)))
            .apply()
    }

    private fun failureCodec(mime: String?, codecs: String?): String? {
        val codecsLower = codecs?.lowercase().orEmpty()
        return when {
            mime.equals("video/dolby-vision", true) || codecsLower.startsWith("dvh") -> "hevc"
            mime.equals("video/hevc", true) || codecsLower.startsWith("hvc1") || codecsLower.startsWith("hev1") -> "hevc"
            mime.equals("video/avc", true) || codecsLower.startsWith("avc1") -> "avc"
            mime.equals("video/av01", true) || codecsLower.startsWith("av01") -> "av1"
            mime.equals("video/x-vnd.on2.vp9", true) || codecsLower.startsWith("vp09") -> "vp9"
            else -> null
        }
    }

    private fun failureProfile(codecs: String?): String? {
        val value = codecs?.lowercase().orEmpty()
        return when {
            value.startsWith("dvh") || value.startsWith("hvc1.2") || value.startsWith("hev1.2") -> "Main10"
            value.startsWith("hvc1.1") || value.startsWith("hev1.1") -> "Main"
            else -> null
        }
    }
}

internal actual fun encodePlaybackQueryValue(json: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
