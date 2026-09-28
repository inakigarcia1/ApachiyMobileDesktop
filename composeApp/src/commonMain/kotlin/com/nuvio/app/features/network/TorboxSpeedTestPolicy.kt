package com.nuvio.app.features.network

data class TorboxSpeedSample(
    val speedMbps: Double,
    val measuredAtEpochMs: Long,
    val connectionType: String? = null,
) {
    fun isValid(): Boolean = speedMbps > 0.0 && measuredAtEpochMs > 0L
}

object TorboxSpeedTestPolicy {
    const val MEASURE_DURATION_MS: Long = 6_000L
    const val STALE_AFTER_MS: Long = 6 * 60 * 60 * 1000L
    const val SPEEDTEST_LIST_URL: String =
        "https://api.torbox.app/v1/api/speedtest?test_length=short&region=latm"
    const val RECOMMENDED_BITRATE_FACTOR: Double = 0.65

    fun calculateMbps(bytesDownloaded: Long, elapsedMs: Long): Double? {
        if (bytesDownloaded <= 0L || elapsedMs <= 0L) return null
        val seconds = elapsedMs.toDouble() / 1000.0
        return (bytesDownloaded * 8.0) / seconds / 1_000_000.0
    }

    fun shouldMeasure(
        nowEpochMs: Long,
        sample: TorboxSpeedSample?,
        storedNetworkSignature: String?,
        currentNetworkSignature: String?,
        force: Boolean,
    ): Boolean {
        if (force) return true
        if (sample == null || !sample.isValid()) return true
        if (nowEpochMs - sample.measuredAtEpochMs >= STALE_AFTER_MS) return true
        if (
            currentNetworkSignature != null &&
            storedNetworkSignature != null &&
            currentNetworkSignature != storedNetworkSignature
        ) {
            return true
        }
        return false
    }

    fun recommendedMaxBitrateMbps(speedMbps: Double): Double =
        speedMbps * RECOMMENDED_BITRATE_FACTOR
}

object PlaybackActiveGuard {
    var isPlaybackActive: Boolean = false
}
