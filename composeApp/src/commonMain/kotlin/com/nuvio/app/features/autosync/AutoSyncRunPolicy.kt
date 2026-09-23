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

/** Debug builds follow the stored switches. Release builds always sync, aggressively. */
internal fun effectiveAutoSyncEnabled(
    operatorSettingsVisible: Boolean,
    storedEnabled: Boolean,
    preferredLanguage: String?,
): Boolean = if (operatorSettingsVisible) {
    storedEnabled && isChosenSubtitleLanguage(preferredLanguage)
} else {
    true
}

internal fun effectiveAggressiveMode(
    operatorSettingsVisible: Boolean,
    storedAggressive: Boolean,
): Boolean = !operatorSettingsVisible || storedAggressive

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
