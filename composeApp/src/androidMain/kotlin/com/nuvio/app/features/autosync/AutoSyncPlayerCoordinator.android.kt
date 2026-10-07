@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.autosync

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.app.core.build.ApachiyProductSettings
import com.nuvio.app.features.player.PlayerSubtitleUtils
import com.nuvio.app.features.player.SidecarSubtitleController
import com.nuvio.app.features.player.SubtitleLanguageMatching
import com.nuvio.app.features.player.SubtitleSyncStatus
import com.nuvio.app.features.player.audiosync.AudioSyncFallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "NuvioAutoSyncPlayer"
private const val SPANISH_SYNC_LANGUAGE = "es"

/** The open OpenSubtitles addon: no key, no account, same shape as any Stremio subtitle addon. */
private const val OPEN_SUBTITLES_ADDON = "https://opensubtitles-v3.strem.io"

/** Enough for AutoSync to find a well-timed one without downloading half of OpenSubtitles. */
private const val MAX_EXTRA_CANDIDATES = 5

internal class AutoSyncPlayerCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: ExoPlayer,
    private val sidecar: SidecarSubtitleController,
    private val sourceUrl: String,
    private val sourceAudioUrl: String?,
    private val dataSourceFactory: DataSource.Factory?,
    private val sourceHeaders: Map<String, String>,
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    private val getUseLibass: () -> Boolean,
    private val onMimeTypeSelected: (String) -> Unit,
    private val onSubtitleDelayChanged: (Int) -> Unit,
) {
    private val audioFallback = AudioSyncFallback(
        context = context,
        scope = scope,
        player = player,
        sidecar = sidecar,
        sourceUrl = sourceUrl,
        sourceAudioUrl = sourceAudioUrl,
        dataSourceFactory = dataSourceFactory,
        getSubtitleHeaders = getSubtitleHeaders,
        onSubtitleReplaced = SubtitleSyncStatus::requestSubtitleSwitch,
        onDevNotice = ::showAutoSyncNotice,
    )
    private var job: Job? = null
    private var retryJob: Job? = null
    private var retryContext: RetryContext? = null
    private var retryOperationToken = 0L
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()

    /** Same-language files fetched straight from the open addon, for AutoSync to choose among. */
    private val extraCandidates = java.util.concurrent.CopyOnWriteArrayList<AutoSyncSubtitleCandidate>()
    private var extraCandidatesKey: String? = null
    private var extraCandidatesJob: Job? = null
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null
    private val _retryState = MutableStateFlow(AutoSyncRetryUiState())
    val retryState: StateFlow<AutoSyncRetryUiState> = _retryState.asStateFlow()

    fun setCandidates(value: List<AutoSyncSubtitleCandidate>) {
        candidates = value.distinctBy { it.url }
        audioFallback.setCandidates(candidates.map { Triple(it.url, it.language, it.name) })
    }

    fun setAudioSyncContent(type: String, videoId: String) {
        audioFallback.setContent(type, videoId)
        val key = "$type|$videoId"
        if (key == extraCandidatesKey) return
        extraCandidatesKey = key
        extraCandidates.clear()
        extraCandidatesJob?.cancel()
        extraCandidatesJob = scope.launch { loadExtraCandidates(type, videoId) }
    }

    /**
     * The subtitle the user sees is whichever one the addons ranked first, and a release this one
     * was not made for cannot be retimed into place at all: on Smallville S1E1 the chosen file runs
     * four minutes longer than the video's own subtitle tracks and no reference in four languages
     * matches it. Offering AutoSync a few more files in the same language lets it pick one that is
     * actually timed for this release, which stays invisible to the user: still one subtitle.
     */
    private suspend fun loadExtraCandidates(type: String, videoId: String) {
        val canonicalType = if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()
        val url = "$OPEN_SUBTITLES_ADDON/subtitles/$canonicalType/$videoId.json"
        val found = runCatching {
            val body = AutomaticSubtitleSync.downloadSubtitleBody(url = url, headers = emptyMap())
            val array = org.json.JSONObject(body).optJSONArray("subtitles") ?: org.json.JSONArray()
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val subtitleUrl = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val language = item.optString("lang")
                if (!SubtitleLanguageMatching.matchesLanguageCode(language, SPANISH_SYNC_LANGUAGE)) {
                    return@mapNotNull null
                }
                AutoSyncSubtitleCandidate(url = subtitleUrl, language = language, name = "OpenSubtitles")
            }.distinctBy { it.url }.take(MAX_EXTRA_CANDIDATES)
        }.getOrElse { error ->
            Log.w(TAG, "extra subtitle lookup failed for $videoId: ${error.message}")
            emptyList()
        }
        extraCandidates.addAll(found)
    }

    fun setAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    ) {
        appliedListener = listener
    }

    private fun invalidateRetryContext() {
        retryOperationToken++
        retryJob?.cancel()
        retryJob = null
        retryContext = null
        _retryState.value = AutoSyncRetryUiState()
    }

    fun cancel() {
        job?.cancel()
        job = null
        audioFallback.stop()
        invalidateRetryContext()
    }

    fun onManualSubtitleDelayChanged() {
        if (retryJob?.isActive != true) return
        retryOperationToken++
        retryJob?.cancel()
        retryJob = null
        _retryState.value = _retryState.value.copy(
            busy = false,
            exhausted = false,
            status = AutoSyncRetryStatus.IDLE,
        )
    }

    fun dispose() {
        cancel()
        audioFallback.release()
        appliedListener = null
    }

    fun retryWithAnotherReference() {
        val snapshot = retryContext ?: return
        if (retryJob?.isActive == true || _retryState.value.exhausted) return

        val currentGeneration = sidecar.currentGenerationFor(snapshot.subtitleUrl)
        if (
            sidecar.activeSidecarSubtitleKey != snapshot.subtitleUrl ||
            currentGeneration != snapshot.expectedGeneration
        ) {
            invalidateRetryContext()
            return
        }

        val rejectedKeys = mergeRejectedReferenceKeys(
            previous = snapshot.rejectedReferenceKeys,
            referenceKey = snapshot.appliedReference.key,
            equivalentKeys = snapshot.appliedReference.equivalentKeys,
        )
        retryContext = snapshot.copy(rejectedReferenceKeys = rejectedKeys)

        val operationToken = ++retryOperationToken
        _retryState.value = AutoSyncRetryUiState(
            available = true,
            busy = true,
            status = AutoSyncRetryStatus.TRYING,
        )

        retryJob = scope.launch {
            var searchOutcome: AutoSyncReferenceSearchOutcome? = null
            try {
                val resolved = AutomaticSubtitleSync.findTimelineRetime(
                    sourceKey = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    selectedSubtitleUrl = snapshot.subtitleUrl,
                    selectedSubtitleHeaders = snapshot.subtitleHeaders,
                    selectedSubtitleBodyDeferred = CompletableDeferred(snapshot.originalBody),
                    preferredLanguage = SPANISH_SYNC_LANGUAGE,
                    alternativeSubtitles = emptyList(),
                    alternativeSubtitlesProvider = null,
                    excludedReferenceKeys = rejectedKeys,
                    requiredReferenceSource = snapshot.appliedReference.source,
                    onReferenceSearchOutcome = { outcome -> searchOutcome = outcome },
                )

                currentCoroutineContext().ensureActive()
                val activeContext = retryContext ?: return@launch
                if (operationToken != retryOperationToken) return@launch
                if (
                    sidecar.activeSidecarSubtitleKey != snapshot.subtitleUrl ||
                    sidecar.currentGenerationFor(snapshot.subtitleUrl) != snapshot.expectedGeneration
                ) {
                    invalidateRetryContext()
                    return@launch
                }

                if (resolved == null) {
                    val exhausted = searchOutcome == AutoSyncReferenceSearchOutcome.EXHAUSTED
                    _retryState.value = AutoSyncRetryUiState(
                        available = true,
                        busy = false,
                        exhausted = exhausted,
                        status = if (exhausted) {
                            AutoSyncRetryStatus.EXHAUSTED
                        } else {
                            AutoSyncRetryStatus.FAILED
                        },
                    )
                    val outcome = searchOutcome ?: AutoSyncReferenceSearchOutcome.UNAVAILABLE
                    AutoSyncDebugLog.info {
                        "RETRY operation=$operationToken outcome=$outcome"
                    }
                    if (AutoSyncDebugLog.ENABLED) {
                        AutoSyncDebugLog.finishAndCopy(
                            context = context,
                            decision = "REFERENCE RETRY $outcome - current timing kept",
                        )
                    }
                    return@launch
                }

                if (
                    resolved.subtitleUrl != snapshot.subtitleUrl ||
                    resolved.reference.source != snapshot.appliedReference.source
                ) {
                    _retryState.value = AutoSyncRetryUiState(
                        available = true,
                        status = AutoSyncRetryStatus.FAILED,
                    )
                    AutoSyncDebugLog.warn {
                        "RETRY operation=$operationToken rejected unexpected external/reference source"
                    }
                    if (AutoSyncDebugLog.ENABLED) {
                        AutoSyncDebugLog.finishAndCopy(
                            context = context,
                            decision = "REFERENCE RETRY unavailable - current timing kept",
                        )
                    }
                    return@launch
                }

                val applied = replaceAutoSyncSidecarSubtitle(
                    sidecar = sidecar,
                    expectedCurrentUrl = snapshot.subtitleUrl,
                    url = snapshot.subtitleUrl,
                    headers = snapshot.subtitleHeaders,
                    rawBody = snapshot.originalBody,
                    useLibass = getUseLibass(),
                    timeline = resolved.timeline,
                )
                currentCoroutineContext().ensureActive()
                if (operationToken != retryOperationToken) return@launch

                if (!applied) {
                    if (
                        sidecar.activeSidecarSubtitleKey != snapshot.subtitleUrl ||
                        sidecar.currentGenerationFor(snapshot.subtitleUrl) != snapshot.expectedGeneration
                    ) {
                        invalidateRetryContext()
                    } else {
                        _retryState.value = AutoSyncRetryUiState(
                            available = true,
                            status = AutoSyncRetryStatus.FAILED,
                        )
                    }
                    AutoSyncDebugLog.warn {
                        "RETRY operation=$operationToken apply=false"
                    }
                    if (AutoSyncDebugLog.ENABLED) {
                        AutoSyncDebugLog.finishAndCopy(
                            context = context,
                            decision = "REFERENCE RETRY apply failed - current timing kept",
                        )
                    }
                    return@launch
                }

                val committedGeneration =
                    sidecar.currentGenerationFor(snapshot.subtitleUrl)
                        ?: run {
                            invalidateRetryContext()
                            return@launch
                        }
                retryContext = activeContext.copy(
                    appliedReference = resolved.reference,
                    expectedGeneration = committedGeneration,
                )
                onSubtitleDelayChanged(0)
                appliedListener?.invoke(snapshot.subtitleUrl, 0)
                _retryState.value = AutoSyncRetryUiState(
                    available = true,
                    status = AutoSyncRetryStatus.UPDATED,
                )
                AutoSyncDebugLog.info {
                    "RETRY operation=$operationToken applied=true " +
                        "reference=${resolved.reference.key} originalBody=true"
                }
                if (AutoSyncDebugLog.ENABLED) {
                    AutoSyncDebugLog.finishAndCopy(
                        context = context,
                        decision = "REFERENCE RETRY applied reference=${resolved.reference.key}",
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (operationToken == retryOperationToken && retryContext != null) {
                    _retryState.value = AutoSyncRetryUiState(
                        available = true,
                        status = AutoSyncRetryStatus.FAILED,
                    )
                }
                AutoSyncDebugLog.error(error) {
                    "RETRY operation=$operationToken failed"
                }
                if (operationToken == retryOperationToken && AutoSyncDebugLog.ENABLED) {
                    AutoSyncDebugLog.finishAndCopy(
                        context = context,
                        decision = "REFERENCE RETRY error - current timing kept",
                    )
                }
            } finally {
                if (operationToken == retryOperationToken) {
                    retryJob = null
                    if (_retryState.value.busy) {
                        _retryState.value = _retryState.value.copy(busy = false)
                    }
                }
            }
        }
    }

    fun start(
        url: String,
        candidateScope: AutoSyncCandidateScope,
        fallbackAttach: (String) -> Unit,
        userChoseSubtitle: Boolean = false,
        isStillSelected: () -> Boolean = { true },
    ) {
        cancel()

        AutoSyncPreferencesRepository.ensureLoaded()
        val operatorVisible = com.nuvio.app.core.build.ApachiyProductSettings.operatorSettingsVisible
        val storedEnabled = AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value
        val enabled = effectiveAutoSyncEnabled(
            operatorSettingsVisible = operatorVisible,
            storedEnabled = storedEnabled,
        )
        when (
            decideAutoSyncStart(
                enabled = enabled,
            )
        ) {
            AutoSyncStartAction.RUN -> Unit
            AutoSyncStartAction.ATTACH_ORIGINAL -> {
                fallbackAttach(url)
                return
            }
        }

        showAutoSyncNotice("Auto Sync V2 started")

        val useLibass = getUseLibass()
        val subtitleHeaders = getSubtitleHeaders(url)
        if (!sidecar.canAttachAddonSubtitleViaSidecar(url, useLibass)) {
            fallbackAttach(url)
            showAutoSyncNotice("Auto Sync V2 failed: unsupported subtitle renderer")
            return
        }

        if (
            !sidecar.startSidecarAddonSubtitle(
                url = url,
                headers = subtitleHeaders,
                useLibass = useLibass,
                rawBodyLoader = {
                    AutomaticSubtitleSync.downloadSubtitleBody(
                        url = url,
                        headers = subtitleHeaders,
                    )
                },
            )
        ) {
            fallbackAttach(url)
            showAutoSyncNotice("Auto Sync V2 failed: subtitle could not be loaded")
            return
        }

        val selectedSubtitleBodyDeferred = sidecar.rawBodyDeferredFor(url)

        audioFallback.setMayReplaceSubtitle(!userChoseSubtitle)
        audioFallback.arm()

        fun restoreOriginalSubtitleIfSidecarFailed() {
            if (!isStillSelected()) return
            if (
                shouldRestoreOriginalSubtitle(
                    activeSidecarSubtitleKey = sidecar.activeSidecarSubtitleKey,
                )
            ) {
                fallbackAttach(url)
            }
        }

        onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(url))
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        job = scope.launch {
            var noSubtitleTracks = false
            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = sourceUrl,
                sourceHeaders = sourceHeaders,
                selectedSubtitleUrl = url,
                selectedSubtitleHeaders = subtitleHeaders,
                selectedSubtitleBodyDeferred = selectedSubtitleBodyDeferred,
                preferredLanguage = SPANISH_SYNC_LANGUAGE,
                alternativeSubtitles = candidateScope.alternativeCandidates(candidates + extraCandidates),
                alternativeSubtitlesProvider = if (candidateScope.usesAlternativeProvider) {
                    { candidates + extraCandidates }
                } else {
                    null
                },
                onReferenceReady = {},
                onNoSubtitleTracks = { noSubtitleTracks = true },
            )
            AutoSyncDebugLog.info {
                "candidateScope=${candidateScope.name}"
            }

            if (resolved == null) {
                // Embedded tracks sit on the video's own timeline, so they answer in seconds and
                // without a byte of audio. When the file has them, listening to the audio for two
                // minutes to reach a shakier answer is the wrong trade: fix the embedded match.
                val handedToAudio =
                    noSubtitleTracks &&
                        sidecar.activeSidecarSubtitleKey == url &&
                        isStillSelected() &&
                        audioFallback.takeOver(url)
                if (handedToAudio) {
                    showAutoSyncNotice("Audio Sync started")
                    return@launch
                }
                restoreOriginalSubtitleIfSidecarFailed()
                if (AutoSyncDebugLog.ENABLED) {
                    AutoSyncDebugLog.finishAndCopy(
                        context = context,
                        decision = "REJECT V2 - original sidecar timing kept",
                    )
                }
                showAutoSyncNotice(
                    if (noSubtitleTracks) "No subtitles in tracks" else "Auto Sync V2 failed: no reliable match",
                )
                return@launch
            }

            audioFallback.disarm()

            val chosenUrl = resolved.subtitleUrl
            val timeline = resolved.timeline
            val toleranceMs = effectiveSyncToleranceMs(
                operatorSettingsVisible = ApachiyProductSettings.operatorSettingsVisible,
                storedToleranceMs = AutoSyncPreferencesRepository.syncToleranceMs.value,
            )
            val withinToleranceMs = selectedSubtitleWithinToleranceMs(
                toleranceMs = toleranceMs,
                result = timeline,
                selectedSubtitleKept = chosenUrl == url,
            )
            val applied = if (withinToleranceMs != null) {
                sidecar.activeSidecarSubtitleKey == url
            } else if (chosenUrl == url) {
                applyAutoSyncSidecarTimeline(
                    sidecar = sidecar,
                    url = url,
                    timeline = timeline,
                )
            } else if (
                sidecar.activeSidecarSubtitleKey == null &&
                sidecar.startSidecarAddonSubtitle(
                    url = chosenUrl,
                    headers = resolved.subtitleHeaders,
                    useLibass = useLibass,
                    rawBodyLoader = resolved.subtitleBody?.let { body ->
                        suspend { body }
                    },
                )
            ) {
                AutoSyncDebugLog.info {
                    "replacement sidecar attached after selected subtitle load failure"
                }
                applyAutoSyncSidecarTimeline(
                    sidecar = sidecar,
                    url = chosenUrl,
                    timeline = timeline,
                )
            } else {
                replaceAutoSyncSidecarSubtitle(
                    sidecar = sidecar,
                    expectedCurrentUrl = url,
                    url = chosenUrl,
                    headers = resolved.subtitleHeaders,
                    rawBody = resolved.subtitleBody,
                    useLibass = useLibass,
                    timeline = timeline,
                )
            }

            if (!applied) {
                restoreOriginalSubtitleIfSidecarFailed()
                if (AutoSyncDebugLog.ENABLED) {
                    AutoSyncDebugLog.finishAndCopy(
                        context = context,
                        decision =
                            if (chosenUrl == url) {
                                "REJECT V2 - sidecar changed or was unavailable before apply"
                            } else {
                                "REJECT V2 replacement - original sidecar preserved"
                            },
                    )
                }
                showAutoSyncNotice("Auto Sync V2 failed: could not apply sync")
                return@launch
            }

            if (chosenUrl != url) {
                onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(chosenUrl))
            }
            val originalBody = resolved.subtitleBody
            val referenceGeneration = sidecar.currentGenerationFor(chosenUrl)
            val pendingRetryContext =
                if (originalBody != null && referenceGeneration != null) {
                    RetryContext(
                        subtitleUrl = chosenUrl,
                        subtitleHeaders = resolved.subtitleHeaders,
                        originalBody = originalBody,
                        appliedReference = resolved.reference,
                        rejectedReferenceKeys = emptySet(),
                        expectedGeneration = referenceGeneration,
                    )
                } else {
                    null
                }

            onSubtitleDelayChanged(0)
            appliedListener?.invoke(chosenUrl, 0)

            AutoSyncDebugLog.info {
                "AUTO APPLY V2 sidecar=true bufferPreserved=true " +
                    "externalChanged=${chosenUrl != url} groups=${timeline.groups.size} " +
                    "alignment=${timeline.alignmentSource} " +
                    "targetCoverage=${"%.4f".format(timeline.targetCoverage)} " +
                    "referenceCoverage=${"%.4f".format(timeline.referenceCoverage)} finalDelay=0ms " +
                    "maxShift=${"%.1f".format(timeline.maxAlignmentShiftMs())}ms " +
                    "withinTolerance=${withinToleranceMs != null} toleranceMs=$toleranceMs"
            }
            if (AutoSyncDebugLog.ENABLED) {
                AutoSyncDebugLog.finishAndCopy(
                    context = context,
                    decision =
                        if (withinToleranceMs != null) {
                            "WITHIN TOLERANCE ${withinToleranceMs}ms - original timing kept " +
                                "url=$chosenUrl alignment=${timeline.alignmentSource}"
                        } else {
                            "APPLIED V2 sidecar timeline bufferPreserved=true " +
                                "externalChanged=${chosenUrl != url} url=$chosenUrl " +
                                "alignment=${timeline.alignmentSource}"
                        },
                )
            }

            if (pendingRetryContext != null) {
                retryContext = pendingRetryContext
                _retryState.value = AutoSyncRetryUiState(available = true)
            } else {
                invalidateRetryContext()
            }

            showAutoSyncNotice(
                buildAutoSyncSuccessToast(
                    replacedSubtitle = chosenUrl != url,
                    scale = timeline.alignmentScale,
                    interceptMs = timeline.alignmentInterceptMs,
                    withinToleranceMs = withinToleranceMs,
                ),
            )
            Log.i(
                TAG,
                "applied selected=$url chosen=$chosenUrl alignment=${timeline.alignmentSource}",
            )
        }
    }

    private data class RetryContext(
        val subtitleUrl: String,
        val subtitleHeaders: Map<String, String>,
        val originalBody: String,
        val appliedReference: AutoSyncReferenceIdentity,
        val rejectedReferenceKeys: Set<String>,
        val expectedGeneration: Long,
    )

    private fun showAutoSyncNotice(message: String) {
        Log.d(TAG, message)
        if (!ApachiyProductSettings.operatorSettingsVisible) return
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

private fun buildAutoSyncSuccessToast(
    replacedSubtitle: Boolean,
    scale: Double,
    interceptMs: Double,
    withinToleranceMs: Int?,
): String {
    val driftCorrected = abs(scale - 1.0) >= 0.0005
    val prefix = if (replacedSubtitle) {
        "Auto Sync V2: subtitle replaced"
    } else {
        "Auto Sync V2 succeeded"
    }
    return when {
        withinToleranceMs != null -> "$prefix • in sync (within $withinToleranceMs ms tolerance)"
        driftCorrected -> "$prefix • drift corrected"
        abs(interceptMs) >= 50.0 -> "$prefix • ${formatAutoSyncOffset(interceptMs)}"
        else -> "$prefix • already in sync"
    }
}

private fun formatAutoSyncOffset(offsetMs: Double): String {
    val roundedMs = offsetMs.roundToInt()
    if (abs(roundedMs) < 1_000) {
        return "${if (roundedMs > 0) "+" else ""}$roundedMs ms"
    }
    val tenths = (roundedMs / 100.0).roundToInt()
    val whole = tenths / 10
    val decimal = abs(tenths % 10)
    return "${if (tenths > 0) "+" else ""}$whole.$decimal s"
}

