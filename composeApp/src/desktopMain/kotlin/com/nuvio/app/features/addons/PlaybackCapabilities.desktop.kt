package com.nuvio.app.features.addons

import java.awt.GraphicsEnvironment
import java.util.Base64

internal actual object PlaybackCapabilitiesProvider {
    private var monitorScreen: PlaybackScreenDto? = null

    fun setMonitorSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            monitorScreen = PlaybackScreenDto(width, height)
        }
    }

    actual fun snapshot(): PlaybackCapabilitiesPayload? {
        val screen = monitorScreen ?: readPrimaryMonitor() ?: return null
        return PlaybackCapabilitiesPayload(
            screen = screen,
            playerBackend = "libmpv",
        )
    }

    private fun readPrimaryMonitor(): PlaybackScreenDto? {
        val device = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        }.getOrNull() ?: return null
        val mode = device.displayMode
        return PlaybackScreenDto(mode.width, mode.height).takeIf { it.width > 0 && it.height > 0 }
    }
}

internal actual fun encodePlaybackQueryValue(json: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
