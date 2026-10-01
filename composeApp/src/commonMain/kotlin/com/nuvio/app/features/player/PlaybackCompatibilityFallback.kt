package com.nuvio.app.features.player

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_source_incompatible_trying_next
import org.jetbrains.compose.resources.getString

object PlaybackCompatibilitySignals {
    @Volatile
    private var compatibilityFailure: Boolean = false

    fun markCompatibilityFailure() {
        compatibilityFailure = true
    }

    fun consumeCompatibilityFailure(): Boolean {
        val value = compatibilityFailure
        compatibilityFailure = false
        return value
    }
}

fun playbackMessageLooksLikeCompatibilityFailure(message: String): Boolean {
    val text = message.lowercase()
    val external = listOf(
        "timeout",
        "timed out",
        "http",
        "network",
        "stremthru",
        "torbox",
        "not found",
        "404",
        "403",
        "500",
        "connection",
        "socket",
    )
    if (external.any { it in text }) return false
    val compatibility = listOf(
        "decoder",
        "codec",
        "unsupported",
        "exceeds",
        "hevc",
        "h265",
        "h.265",
        "av1",
        "profile",
        "no video",
    )
    return compatibility.any { it in text }
}

internal fun PlayerScreenRuntime.tryNextCompatibleSource(failureMessage: String): Boolean {
    if (compatibilityAttemptedUrls.size >= 6) return false
    val current = activeSourceUrl
    if (current.isNotBlank()) compatibilityAttemptedUrls.add(current)
    scope.launch {
        NuvioToastController.show(
            message = getString(Res.string.player_source_incompatible_trying_next),
            durationMillis = 4000L,
        )
    }
    nextCompatibleCandidate()?.let { next ->
        announceAndSwitch(next)
        return true
    }
    val type = contentType ?: parentMetaType
    val id = activeVideoId ?: videoId
    if (id.isNullOrBlank() || compatibilityReloadStarted) return false
    compatibilityReloadStarted = true
    scope.launch {
        PlayerStreamsRepository.loadSources(
            type = type,
            videoId = id,
            season = activeSeasonNumber,
            episode = activeEpisodeNumber,
            runtimeMinutes = runtimeMinutes,
        )
        for (attempt in 0 until 20) {
            nextCompatibleCandidate()?.let { next ->
                announceAndSwitch(next)
                return@launch
            }
            val loading = PlayerStreamsRepository.sourceState.value.isAnyLoading
            if (!loading && attempt > 3) break
            delay(250)
        }
        errorMessage = failureMessage
    }
    return true
}

private fun PlayerScreenRuntime.announceAndSwitch(stream: StreamItem) {
    stream.playableDirectUrl?.let { compatibilityAttemptedUrls.add(it) }
    errorMessage = null
    switchToSource(stream)
}

private fun PlayerScreenRuntime.nextCompatibleCandidate(): StreamItem? {
    val loaded = PlayerStreamsRepository.sourceState.value.allStreams
    val browsed = StreamsRepository.uiState.value.groups.flatMap { it.streams }
    return (loaded + browsed).firstOrNull { stream ->
        val url = stream.playableDirectUrl
        !url.isNullOrBlank() && url !in compatibilityAttemptedUrls && url != activeSourceUrl
    }
}
