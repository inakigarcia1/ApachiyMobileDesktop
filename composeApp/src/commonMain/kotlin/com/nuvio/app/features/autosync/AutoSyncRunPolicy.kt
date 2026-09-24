package com.nuvio.app.features.autosync

internal enum class AutoSyncCandidateScope {
    STARTUP_SEARCH,
    SELECTED_ONLY,
}

internal fun decideAutoSyncStart(enabled: Boolean): AutoSyncStartAction =
    if (enabled) AutoSyncStartAction.RUN else AutoSyncStartAction.ATTACH_ORIGINAL

internal fun isChosenSubtitleLanguage(language: String?): Boolean {
    val value = language?.trim().orEmpty()
    return value.isNotEmpty() &&
        !value.equals("none", ignoreCase = true) &&
        !value.equals("forced", ignoreCase = true)
}

/** Debug builds follow the stored switch. Release builds always sync. The run is always the Spanish subtitle. */
internal fun effectiveAutoSyncEnabled(
    operatorSettingsVisible: Boolean,
    storedEnabled: Boolean,
): Boolean = if (operatorSettingsVisible) storedEnabled else true

internal fun effectiveAggressiveMode(
    operatorSettingsVisible: Boolean,
    storedAggressive: Boolean,
): Boolean = !operatorSettingsVisible || storedAggressive

/** Debug builds honor the stored tolerance. Release builds always retime. */
internal fun effectiveSyncToleranceMs(
    operatorSettingsVisible: Boolean,
    storedToleranceMs: Int,
): Int = if (
    operatorSettingsVisible &&
    storedToleranceMs in AutoSyncPreferencesRepository.syncToleranceOptionsMs
) {
    storedToleranceMs
} else {
    0
}

internal enum class AutoSyncStartAction {
    RUN,
    ATTACH_ORIGINAL,
}

internal fun AutoSyncCandidateScope.alternativeCandidates(
    candidates: List<AutoSyncSubtitleCandidate>,
): List<AutoSyncSubtitleCandidate> =
    if (this == AutoSyncCandidateScope.STARTUP_SEARCH) candidates else emptyList()

internal val AutoSyncCandidateScope.usesAlternativeProvider: Boolean
    get() = this == AutoSyncCandidateScope.STARTUP_SEARCH

internal fun shouldRestoreOriginalSubtitle(
    activeSidecarSubtitleKey: String?,
): Boolean = activeSidecarSubtitleKey == null
