package com.nuvio.app.features.addons

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal actual object PlaybackCapabilitiesProvider {
    actual fun snapshot(): PlaybackCapabilitiesPayload? = null
}

@OptIn(ExperimentalEncodingApi::class)
internal actual fun encodePlaybackQueryValue(json: String): String =
    Base64.UrlSafe.encode(json.encodeToByteArray()).trimEnd('=')
