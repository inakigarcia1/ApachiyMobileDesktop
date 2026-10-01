package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.core.diagnostics.PlaybackFailureNotes
import com.nuvio.app.core.diagnostics.PlaybackFailureReport
import com.nuvio.app.core.diagnostics.playbackReportHost
import com.nuvio.app.core.diagnostics.reportPlaybackFailure
import com.nuvio.app.core.diagnostics.scrubPlaybackText
import com.nuvio.app.isDesktop
import com.nuvio.app.isIos

private val log = Logger.withTag("PlaybackFailure")

internal fun PlayerScreenRuntime.onVisiblePlaybackFailure(message: String?) {
    if (message.isNullOrBlank()) {
        reportedPlaybackFailureMessage = null
        return
    }
    if (reportedPlaybackFailureMessage == message) return
    reportedPlaybackFailureMessage = message

    val note = PlaybackFailureNotes.takeMatching(message)
    val report = PlaybackFailureReport(
        message = scrubPlaybackText(message) ?: "playback failed",
        engine = note?.engine ?: fallbackPlaybackEngine(),
        host = playbackReportHost(activeSourceUrl),
        provider = scrubPlaybackText(activeProviderName),
        streamType = scrubPlaybackText(activeStreamType),
        streamLabel = scrubPlaybackText(activeStreamTitle),
        contentId = scrubPlaybackText(activeVideoId ?: parentMetaId),
        title = scrubPlaybackText(title),
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
        errorCode = scrubPlaybackText(note?.errorCode),
        exceptionClass = scrubPlaybackText(note?.exceptionClass),
        causeClass = scrubPlaybackText(note?.causeClass),
        causeMessage = scrubPlaybackText(note?.causeMessage),
        videoCodec = scrubPlaybackText(note?.videoCodec),
        mimeType = scrubPlaybackText(note?.mimeType),
    )
    log.e {
        "playback failure engine=${report.engine} host=${report.host} code=${report.errorCode} message=${report.message}"
    }
    reportPlaybackFailure(report)
}

private fun PlayerScreenRuntime.fallbackPlaybackEngine(): String = when {
    isDesktop -> "desktop-libmpv"
    isIos -> "ios"
    else -> "android-${playerSettingsUiState.androidPlaybackEngine.name.lowercase()}"
}
