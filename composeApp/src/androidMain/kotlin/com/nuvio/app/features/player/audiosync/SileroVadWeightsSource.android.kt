package com.nuvio.app.features.player.audiosync

import android.content.Context
import com.nuvio.app.R
import java.io.InputStream

internal actual object SileroVadWeightsSource {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun open(): InputStream? =
        appContext?.resources?.openRawResource(R.raw.silero_vad_v5_16k)
}
