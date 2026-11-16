package com.nuvio.app.features.player

import androidx.compose.runtime.snapshotFlow
import com.nuvio.app.core.build.ApachiyProductSettings
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import com.nuvio.app.features.autosync.CommunityAutoSyncAttempt
import com.nuvio.app.features.autosync.CommunityAutoSyncRetime
import com.nuvio.app.features.autosync.OPEN_SUBTITLES_FIRST_BUDGET_MS
import com.nuvio.app.features.autosync.OpenSubtitlesExtraCandidates
import com.nuvio.app.features.autosync.effectiveAutoSyncEnabled
import com.nuvio.app.features.autosync.effectiveSyncToleranceMsForCommunity
import com.nuvio.app.features.autosync.renderRetimedSrt
import com.nuvio.app.features.autosync.selectedSubtitleWithinToleranceMs
import com.nuvio.app.features.player.embedded.DialogueTimingIndex
import com.nuvio.app.features.player.embedded.EmbeddedSubtitleExtractor
import com.nuvio.app.isIos
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

internal suspend fun PlayerScreenRuntime.runOpenSubtitlesFirst(): Boolean {
    if (isUserExplicitSubtitleSelection) return true
    val syncType = contentType?.takeIf { it.isNotBlank() }
        ?: activeAddonSubtitleType.takeIf { it.isNotBlank() }
    val syncVideoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return false
    if (syncType == null) return false

    val candidates = OpenSubtitlesExtraCandidates.load(syncType, syncVideoId)
    if (candidates.isEmpty()) {
        showAutoSyncNotice("Auto Sync: OpenSubtitles had no candidates, using community")
        return false
    }

    val controller = snapshotFlow { playerController }.filterNotNull().first()
    val source = httpTimingSourceUrl().orEmpty()
    val headers = sanitizePlaybackHeaders(activeSourceHeaders)
    val candidateUrls = candidates.map { it.url }

    var engineHandled = false
    val appliedUrl = withTimeoutOrNull(OPEN_SUBTITLES_FIRST_BUDGET_MS) {
        val deferred = CompletableDeferred<String?>()
        val engineStarted = controller.runOpenSubtitlesFirstAutoSync(
            sourceUrl = source,
            sourceHeaders = headers,
            candidateUrls = candidateUrls,
        ) { url ->
            deferred.complete(url)
        }
        engineHandled = engineStarted
        if (engineStarted) {
            deferred.await()
        } else {
            runOpenSubtitlesFirstCommonPath(
                controller = controller,
                headers = headers,
                candidates = candidates,
            )
        }
    }

    if (isUserExplicitSubtitleSelection) return true
    if (appliedUrl.isNullOrBlank()) {
        showAutoSyncNotice("Auto Sync: OpenSubtitles no match, trying community")
        return false
    }

    markOpenSubtitlesFirstApplied(appliedUrl, deselectTracks = engineHandled)
    showAutoSyncNotice("Auto Sync V2 succeeded • OpenSubtitles")
    return true
}

private suspend fun PlayerScreenRuntime.runOpenSubtitlesFirstCommonPath(
    controller: PlayerEngineController,
    headers: Map<String, String>,
    candidates: List<com.nuvio.app.features.autosync.AutoSyncSubtitleCandidate>,
): String? {
    val index = loadDialogueTimingIndex()
    if (index.tracks.isEmpty()) {
        return null
    }
    val extraUrls = candidates.drop(1).map { it.url }
    val attempt = retimeCommunityAutoSyncAgainstIndex(
        index = index,
        subtitleUrl = candidates.first().url,
        headers = emptyMap(),
        extraUrls = extraUrls,
    ) ?: return null
    if (isUserExplicitSubtitleSelection) return null
    val rewritten = renderRetimedSrt(attempt.cues, attempt.timeline)
    // Turn off the embedded track before adding the synced file: on libmpv "off" is sid=no,
    // so doing it afterwards hides the file we just added.
    controller.selectSubtitleTrack(-1)
    syncedSubtitleBodies[attempt.subtitleUrl] = rewritten
    val replaced = controller.replaceExternalSubtitleBody(attempt.subtitleUrl, rewritten)
    return if (replaced) {
        attempt.subtitleUrl
    } else {
        null
    }
}

private fun PlayerScreenRuntime.markOpenSubtitlesFirstApplied(url: String, deselectTracks: Boolean) {
    appliedAddonSubtitleUrl = url
    useCustomSubtitles = true
    selectedSubtitleIndex = -1
    preferredSubtitleSelectionApplied = true
    val id = "opensubtitles:${url.hashCode()}"
    selectedAddonSubtitleId = id
    // The common path already deselected before adding the file (see runOpenSubtitlesFirstCommonPath).
    if (deselectTracks) playerController?.selectSubtitleTrack(-1)
    playerController?.setSubtitleDelayMs(0)
    SubtitleRepository.publish(
        listOf(
            AddonSubtitle(
                id = id,
                url = url,
                language = "es",
                // Release users only see the language; the source is a debug detail.
                display = if (ApachiyProductSettings.operatorSettingsVisible) "Español • OpenSubtitles" else "Español",
                addonName = if (ApachiyProductSettings.operatorSettingsVisible) "OpenSubtitles" else "",
                isSelected = true,
            ),
        ),
    )
}

private suspend fun PlayerScreenRuntime.retimeCommunityAutoSyncAgainstIndex(
    index: DialogueTimingIndex,
    subtitleUrl: String,
    headers: Map<String, String>,
    extraUrls: List<String>,
): CommunityAutoSyncAttempt? {
    var currentIndex = index
    var attempt = CommunityAutoSyncRetime.retimeAgainstIndex(
        index = currentIndex,
        subtitleUrl = subtitleUrl,
        headers = headers,
        extraSubtitleUrls = extraUrls,
    )
    if (attempt == null && CommunityAutoSyncRetime.indexNeedsPgsSemanticFallback(currentIndex, attempt)) {
        val sourceUrl = httpTimingSourceUrl()
        if (sourceUrl != null) {
            val semantic = EmbeddedSubtitleExtractor.loadPgsSemanticDialogueTiming(
                sourceUrl = sourceUrl,
                headers = sanitizePlaybackHeaders(activeSourceHeaders),
            )
            if (semantic != null && semantic.tracks.isNotEmpty()) {
                dialogueTimingIndex = semantic
                currentIndex = semantic
                attempt = CommunityAutoSyncRetime.retimeAgainstIndex(
                    index = currentIndex,
                    subtitleUrl = subtitleUrl,
                    headers = headers,
                    extraSubtitleUrls = extraUrls,
                )
            }
        }
    }
    return attempt
}

internal fun PlayerScreenRuntime.launchCommunityAutoSync(subtitleUrl: String) {
    if (isIos) return
    AutoSyncPreferencesRepository.ensureLoaded()
    val enabled = effectiveAutoSyncEnabled(
        operatorSettingsVisible = ApachiyProductSettings.operatorSettingsVisible,
        storedEnabled = AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value,
    )
    if (!enabled) {
        showAutoSyncNotice("Auto Sync skipped: disabled in settings")
        return
    }
    if (dialogueTimingIndex?.hasEmbeddedSpanish == true) {
        showAutoSyncNotice("Auto Sync skipped: embedded Spanish track")
        return
    }
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
        val extrasDeferred = async {
            if (syncContentType == null || syncVideoId == null) {
                emptyList()
            } else {
                OpenSubtitlesExtraCandidates.load(syncContentType, syncVideoId).map { it.url }
            }
        }
        var index = loadDialogueTimingIndex()
        if (index.hasEmbeddedSpanish) {
            extrasDeferred.cancel()
            showAutoSyncNotice("Auto Sync skipped: embedded Spanish track")
            return@launch
        }
        if (index.tracks.isEmpty() && index.noSubtitleTracks) {
            extrasDeferred.cancel()
            handOffToDesktopAudioSync(subtitleUrl, headers, syncContentType, syncVideoId)
            return@launch
        }
        val extraUrls = extrasDeferred.await()
        if (index.tracks.isEmpty()) {
            showAutoSyncNotice("Auto Sync V2 failed: embedded reference index empty")
            return@launch
        }
        var attempt = retimeCommunityAutoSyncAgainstIndex(
            index = index,
            subtitleUrl = subtitleUrl,
            headers = headers,
            extraUrls = extraUrls,
        )
        if (attempt == null) {
            if (index.noSubtitleTracks) {
                handOffToDesktopAudioSync(subtitleUrl, headers, syncContentType, syncVideoId)
            } else {
                showAutoSyncNotice("Auto Sync V2 failed: no reliable match (${index.tracks.size} refs)")
            }
            return@launch
        }
        // OpenSubtitles replaces the addon file. That URL differs on purpose.
        // Bail only when the user picked another subtitle while this run was in flight.
        if (appliedAddonSubtitleUrl != null && appliedAddonSubtitleUrl != subtitleUrl) {
            return@launch
        }
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
        syncedSubtitleBodies[attempt.subtitleUrl] = rewritten
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
    // Audio sync posts this from a background dispatcher. Compose only republishes
    // the player toast when the write happens on the UI scope.
    scope.launch {
        playerNotificationMessage = message
        playerNotificationToken += 1L
    }
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
