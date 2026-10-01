package com.nuvio.app.core.diagnostics

internal actual fun reportPlaybackFailure(report: PlaybackFailureReport) {
    SentryInitializer.reportPlaybackFailure(report)
}
