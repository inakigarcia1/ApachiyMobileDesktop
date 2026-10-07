package com.nuvio.app.features.player.audiosync

import java.io.InputStream

internal expect object SileroVadWeightsSource {
    fun open(): InputStream?
}

internal fun loadSileroVadWeights(): SileroVadWeights? =
    runCatching { SileroVadWeightsSource.open()?.use(SileroVadWeights::read) }.getOrNull()
