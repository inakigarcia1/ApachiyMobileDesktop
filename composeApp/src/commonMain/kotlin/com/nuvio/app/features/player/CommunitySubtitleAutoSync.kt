package com.nuvio.app.features.player

import com.nuvio.app.core.build.ApachiyProductSettings
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import com.nuvio.app.features.autosync.CommunityAutoSyncRetime
import com.nuvio.app.features.autosync.OpenSubtitlesExtraCandidates
import com.nuvio.app.features.autosync.effectiveAutoSyncEnabled
import com.nuvio.app.features.autosync.effectiveSyncToleranceMsForCommunity
import com.nuvio.app.features.autosync.renderRetimedSrt
import com.nuvio.app.features.autosync.selectedSubtitleWithinToleranceMs
import com.nuvio.app.features.player.embedded.DialogueTimingIndex
import com.nuvio.app.features.player.embedded.EmbeddedSubtitleExtractor
import com.nuvio.app.isIos
import kotlinx.coroutines.launch

internal suspend fun PlayerScreenRuntime.loadDialogueTimingIndex(): DialogueTimingIndex {
    val cached = dialogueTimingIndex
    if (cached != null) return cached
    val source = httpTimingSourceUrl()
    val loaded = if (source == null) {
        DialogueTimingIndex()
    } else {
        EmbeddedSubtitleExtractor.loadDialogueTiming(
            sourceUrl = source,
            headers = sanitizePlaybackHeaders(activeSourceHeaders),
        )
    }
    dialogueTimingIndex = loaded
    return loaded
}

internal fun PlayerScreenRuntime.launchCommunityAutoSync(subtitleUrl: String) {
    if (isIos) return
    AutoSyncPreferencesRepository.ensureLoaded()
    val enabled = effectiveAutoSyncEnabled(
        operatorSettingsVisible = ApachiyProductSettings.operatorSettingsVisible,
        storedEnabled = AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value,
    )
    if (!enabled) return
    if (dialogueTimingIndex?.hasEmbeddedSpanish == true) return
    communityAutoSyncJob?.cancel()
    val source = httpTimingSourceUrl().orEmpty()
    val headers = sanitizePlaybackHeaders(activeSourceHeaders)
    if (playerController?.runSelectedAutoSync(
            sourceUrl = source,
            sourceHeaders = headers,
            subtitleUrl = subtitleUrl,
            subtitleHeaders = headers,
            userChoseSubtitle = isUserExplicitSubtitleSelection,
            isStillSelected = { appliedAddonSubtitleUrl == subtitleUrl && useCustomSubtitles },
        ) == true
    ) {
        return
    }
    val syncContentType = this.contentType?.takeIf { it.isNotBlank() }
    val syncVideoId = activeVideoId?.takeIf { it.isNotBlank() }
    communityAutoSyncJob = scope.launch {
        showAutoSyncNotice("Auto Sync V2 started")
        var index = loadDialogueTimingIndex()
        if (index.hasEmbeddedSpanish) return@launch
        if (index.tracks.isEmpty() && index.noSubtitleTracks) {
            handOffToDesktopAudioSync(subtitleUrl, headers, syncContentType, syncVideoId)
            return@launch
        }
        if (index.tracks.isEmpty()) {
            showAutoSyncNotice("Auto Sync V2 failed: no reliable match")
            return@launch
        }
        val extraUrls = if (syncContentType != null && syncVideoId != null) {
            OpenSubtitlesExtraCandidates.load(syncContentType, syncVideoId).map { it.url }
        } else {
            emptyList()
        }
        var attempt = CommunityAutoSyncRetime.retimeAgainstIndex(
            index = index,
            subtitleUrl = subtitleUrl,
            headers = headers,
            extraSubtitleUrls = extraUrls,
        )
        if (attempt == null && CommunityAutoSyncRetime.indexNeedsPgsSemanticFallback(index, attempt)) {
            val sourceUrl = httpTimingSourceUrl()
            if (sourceUrl != null) {
                val semantic = EmbeddedSubtitleExtractor.loadPgsSemanticDialogueTiming(
                    sourceUrl = sourceUrl,
                    headers = headers,
                )
                if (semantic != null && semantic.tracks.isNotEmpty()) {
                    dialogueTimingIndex = semantic
                    index = semantic
                    attempt = CommunityAutoSyncRetime.retimeAgainstIndex(
                        index = index,
                        subtitleUrl = subtitleUrl,
                        headers = headers,
                        extraSubtitleUrls = extraUrls,
                    )
                }
            }
        }
        if (attempt == null) {
            if (index.noSubtitleTracks) {
                handOffToDesktopAudioSync(subtitleUrl, headers, syncContentType, syncVideoId)
            } else {
                showAutoSyncNotice("Auto Sync V2 failed: no reliable match")
            }
            return@launch
        }
        if (appliedAddonSubtitleUrl != null && appliedAddonSubtitleUrl != attempt.subtitleUrl) return@launch
        val withinToleranceMs = selectedSubtitleWithinToleranceMs(
            toleranceMs = effectiveSyncToleranceMsForCommunity(),
            result = attempt.timeline,
            selectedSubtitleKept = attempt.subtitleUrl == subtitleUrl,
        )
        if (withinToleranceMs != null) {
            playerController?.setSubtitleDelayMs(0)
            showAutoSyncNotice(
                "Auto Sync V2 succeeded • in sync (within $withinToleranceMs ms tolerance)",
            )
            return@launch
        }
        val rewritten = renderRetimedSrt(attempt.cues, attempt.timeline)
        val applied = playerController?.replaceExternalSubtitleBody(attempt.subtitleUrl, rewritten) == true
        if (!applied) {
            showAutoSyncNotice("Auto Sync V2 failed: could not apply sync")
            return@launch
        }
        if (attempt.subtitleUrl != subtitleUrl) {
            appliedAddonSubtitleUrl = attempt.subtitleUrl
        }
        playerController?.setSubtitleDelayMs(0)
        val timeline = attempt.timeline
        val driftCorrected = kotlin.math.abs(timeline.alignmentScale - 1.0) >= 0.0005
        val prefix = "Auto Sync V2 succeeded"
        showAutoSyncNotice(
            when {
                driftCorrected -> "$prefix • drift corrected"
                kotlin.math.abs(timeline.alignmentInterceptMs) >= 50.0 ->
                    "$prefix • ${kotlin.math.round(timeline.alignmentInterceptMs).toInt()}ms"
                else -> "$prefix • already in sync"
            },
        )
    }
}

private suspend fun PlayerScreenRuntime.handOffToDesktopAudioSync(
    subtitleUrl: String,
    headers: Map<String, String>,
    contentType: String?,
    videoId: String?,
) {
    val handedOff = playerController?.runDesktopAudioSyncFallback(
        subtitleUrl = subtitleUrl,
        subtitleHeaders = headers,
        contentType = contentType,
        videoId = videoId,
        isStillSelected = { appliedAddonSubtitleUrl == subtitleUrl && useCustomSubtitles },
    ) == true
    if (!handedOff) {
        showAutoSyncNotice("Auto Sync V2 failed: no reliable match")
    }
}

internal fun PlayerScreenRuntime.showAutoSyncNotice(message: String) {
    if (!ApachiyProductSettings.operatorSettingsVisible) return
    playerNotificationMessage = message
    playerNotificationToken += 1L
}

internal fun PlayerScreenRuntime.cancelCommunityAutoSync() {
    communityAutoSyncJob?.cancel()
    communityAutoSyncJob = null
    dialogueTimingIndex = null
}

private fun PlayerScreenRuntime.httpTimingSourceUrl(): String? =
    listOfNotNull(activeSourceUrl, p2pResolvedSourceUrl)
        .firstOrNull { url ->
            url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true)
        }
