package com.nuvio.app.features.player

import com.nuvio.app.core.build.ApachiyProductSettings
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import com.nuvio.app.features.autosync.AutoSyncSelectedRun
import com.nuvio.app.features.autosync.effectiveAutoSyncEnabled
import com.nuvio.app.features.autosync.effectiveSyncToleranceMs
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
        ) == true
    ) {
        return
    }
    communityAutoSyncJob = scope.launch {
        showAutoSyncNotice("Auto Sync V2 started")
        val index = loadDialogueTimingIndex()
        if (index.hasEmbeddedSpanish) return@launch
        if (index.tracks.isEmpty()) {
            showAutoSyncNotice("Auto Sync V2 failed: no reliable match")
            return@launch
        }
        val body = runCatching {
            httpGetTextWithHeaders(url = subtitleUrl, headers = headers)
        }.getOrNull()
        if (body == null) {
            showAutoSyncNotice("Auto Sync V2 failed: subtitle could not be loaded")
            return@launch
        }
        if (appliedAddonSubtitleUrl != null && appliedAddonSubtitleUrl != subtitleUrl) return@launch
        val cues = PlayerSubtitleCueParser.parse(body, subtitleUrl)
        val timeline = AutoSyncSelectedRun.retime(index.tracks, cues)
        if (timeline == null) {
            showAutoSyncNotice("Auto Sync V2 failed: no reliable match")
            return@launch
        }
        val withinToleranceMs = selectedSubtitleWithinToleranceMs(
            toleranceMs = effectiveSyncToleranceMs(
                operatorSettingsVisible = ApachiyProductSettings.operatorSettingsVisible,
                storedToleranceMs = AutoSyncPreferencesRepository.syncToleranceMs.value,
            ),
            result = timeline,
            selectedSubtitleKept = true,
        )
        if (withinToleranceMs != null) {
            playerController?.setSubtitleDelayMs(0)
            showAutoSyncNotice(
                "Auto Sync V2 succeeded • in sync (within $withinToleranceMs ms tolerance)",
            )
            return@launch
        }
        val rewritten = renderRetimedSrt(cues, timeline)
        val applied = playerController?.replaceExternalSubtitleBody(subtitleUrl, rewritten) == true
        if (!applied) {
            showAutoSyncNotice("Auto Sync V2 failed: could not apply sync")
            return@launch
        }
        playerController?.setSubtitleDelayMs(0)
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

private fun PlayerScreenRuntime.showAutoSyncNotice(message: String) {
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
