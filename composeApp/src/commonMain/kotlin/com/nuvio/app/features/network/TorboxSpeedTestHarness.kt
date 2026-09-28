package com.nuvio.app.features.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal expect object TorboxSpeedTestHarness {
    fun readSample(): TorboxSpeedSample?
    fun readStoredNetworkSignature(): String?
    suspend fun measure(): TorboxSpeedSample?
    fun currentNetworkSignature(): String?
    fun persist(sample: TorboxSpeedSample, networkSignature: String?)
    fun initializePlatform()
}

object TorboxSpeedTestCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var measuring = false

    fun lastSample(): TorboxSpeedSample? = TorboxSpeedTestHarness.readSample()

    fun scheduleBackgroundCheck() {
        scope.launch { runIfNeeded(force = false) }
    }

    fun runManualMeasure(onComplete: (() -> Unit)? = null) {
        scope.launch {
            runIfNeeded(force = true, manual = true)
            onComplete?.invoke()
        }
    }

    suspend fun runIfNeeded(force: Boolean, manual: Boolean = false) {
        if (PlaybackActiveGuard.isPlaybackActive) {
            if (force) TorboxSpeedTestDevFeedback.onSkippedPlaybackActive()
            return
        }
        if (measuring) {
            if (force) TorboxSpeedTestDevFeedback.onSkippedAlreadyMeasuring()
            return
        }
        val now = System.currentTimeMillis()
        val sample = TorboxSpeedTestHarness.readSample()
        val currentSig = TorboxSpeedTestHarness.currentNetworkSignature()
        val storedSig = TorboxSpeedTestHarness.readStoredNetworkSignature()
        if (!TorboxSpeedTestPolicy.shouldMeasure(now, sample, storedSig, currentSig, force)) {
            return
        }
        measuring = true
        TorboxSpeedTestDevFeedback.onMeasureStarted(manual = force || manual)
        try {
            val measured = TorboxSpeedTestHarness.measure()
            if (measured != null && measured.isValid()) {
                TorboxSpeedTestHarness.persist(measured, currentSig)
                TorboxSpeedTestDevFeedback.onMeasureSucceeded(measured.speedMbps, manual = force || manual)
            } else {
                TorboxSpeedTestDevFeedback.onMeasureFailed(manual = force || manual)
            }
        } finally {
            measuring = false
        }
    }
}
