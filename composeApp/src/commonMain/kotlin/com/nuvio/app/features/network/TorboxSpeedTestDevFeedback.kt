package com.nuvio.app.features.network

import com.nuvio.app.core.ui.NuvioToastController
internal expect fun isDevelopmentBuild(): Boolean

internal object TorboxSpeedTestDevFeedback {
    private const val TOAST_MS = 4_500L

    fun onMeasureStarted(manual: Boolean) {
        if (!isDevelopmentBuild()) return
        val prefix = if (manual) "Speedtest TorBox (manual)" else "Speedtest TorBox"
        NuvioToastController.show("$prefix: midiendo ~6s contra CDN latm…", TOAST_MS)
    }

    fun onMeasureSucceeded(mbps: Double, manual: Boolean) {
        if (!isDevelopmentBuild()) return
        val prefix = if (manual) "Speedtest TorBox (manual)" else "Speedtest TorBox"
        NuvioToastController.show("$prefix: listo · ${formatTorboxMbps(mbps)} Mbps guardado", TOAST_MS)
    }

    fun onMeasureFailed(manual: Boolean) {
        if (!isDevelopmentBuild()) return
        val prefix = if (manual) "Speedtest TorBox (manual)" else "Speedtest TorBox"
        NuvioToastController.show(
            "$prefix: falló · no se cambió el valor guardado",
            TOAST_MS,
        )
    }

    fun onSkippedPlaybackActive() {
        if (!isDevelopmentBuild()) return
        NuvioToastController.show(
            "Speedtest TorBox: omitido · hay reproducción activa",
            TOAST_MS,
        )
    }

    fun onSkippedAlreadyMeasuring() {
        if (!isDevelopmentBuild()) return
        NuvioToastController.show(
            "Speedtest TorBox: ya hay una medición en curso",
            TOAST_MS,
        )
    }

}
