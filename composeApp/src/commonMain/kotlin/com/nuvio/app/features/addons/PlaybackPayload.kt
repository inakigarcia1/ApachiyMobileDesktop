package com.nuvio.app.features.addons

import com.nuvio.app.features.network.TorboxSpeedTestHarness

internal fun playbackPayloadForStream(runtimeMinutes: Int?): PlaybackCapabilitiesPayload? {
    val device = PlaybackCapabilitiesProvider.snapshot()
    val speed = TorboxSpeedTestHarness.readSample()?.takeIf { it.isValid() }?.speedMbps
    val runtime = runtimeMinutes?.takeIf { it > 0 }
    if (device == null && speed == null && runtime == null) return null
    val base = device ?: PlaybackCapabilitiesPayload()
    return base.copy(
        speedMbps = speed,
        runtimeMinutes = runtime,
    ).takeIf { it.hasAnyConstraint() }
}
