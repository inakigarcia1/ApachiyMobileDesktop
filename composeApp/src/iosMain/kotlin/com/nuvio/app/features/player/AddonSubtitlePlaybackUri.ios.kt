package com.nuvio.app.features.player

internal actual suspend fun prepareAddonSubtitlePlaybackUri(
    remoteUrl: String,
    sourceHeaders: Map<String, String>,
    cacheKey: String,
): String? = null
