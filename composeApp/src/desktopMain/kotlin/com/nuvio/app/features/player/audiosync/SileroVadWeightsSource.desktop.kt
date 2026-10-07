package com.nuvio.app.features.player.audiosync

import java.io.InputStream

internal actual object SileroVadWeightsSource {
    actual fun open(): InputStream? =
        SileroVadWeightsSource::class.java.getResourceAsStream("/silero_vad_v5_16k.bin")
            ?: run {
                val dev = java.io.File("composeApp/src/androidMain/res/raw/silero_vad_v5_16k.bin")
                if (dev.isFile) dev.inputStream() else null
            }
}
