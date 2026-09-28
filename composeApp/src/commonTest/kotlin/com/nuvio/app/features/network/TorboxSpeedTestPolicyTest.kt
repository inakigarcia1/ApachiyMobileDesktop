package com.nuvio.app.features.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorboxSpeedTestPolicyTest {

    @Test
    fun calculateMbps_from_bytes_and_elapsed() {
        val mbps = TorboxSpeedTestPolicy.calculateMbps(
            bytesDownloaded = 7_500_000,
            elapsedMs = 6_000,
        )
        assertEquals(10.0, mbps!!, absoluteTolerance = 0.5)
    }

    @Test
    fun shouldMeasure_when_stale_or_missing() {
        val now = 10_000_000L
        val stale = TorboxSpeedSample(50.0, now - TorboxSpeedTestPolicy.STALE_AFTER_MS - 1)
        assertTrue(TorboxSpeedTestPolicy.shouldMeasure(now, stale, "wifi:1", "wifi:1", force = false))
        val fresh = TorboxSpeedSample(50.0, now - 1_000)
        assertFalse(TorboxSpeedTestPolicy.shouldMeasure(now, fresh, "wifi:1", "wifi:1", force = false))
        assertTrue(TorboxSpeedTestPolicy.shouldMeasure(now, null, null, null, force = false))
    }

    @Test
    fun formatTorboxMbps_one_decimal_below_100() {
        assertEquals("50.5", formatTorboxMbps(50.48))
        assertEquals("100", formatTorboxMbps(100.4))
    }

    @Test
    fun shouldMeasure_on_network_change() {
        val now = 1_000_000L
        val sample = TorboxSpeedSample(40.0, now - 60_000)
        assertTrue(
            TorboxSpeedTestPolicy.shouldMeasure(now, sample, "wifi:1", "cellular:2", force = false),
        )
    }
}
