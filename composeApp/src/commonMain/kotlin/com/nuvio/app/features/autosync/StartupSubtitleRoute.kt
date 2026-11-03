package com.nuvio.app.features.autosync

internal enum class StartupSubtitleRoute {
    NONE,
    OPEN_SUBTITLES_FIRST,
    COMMUNITY,
}

internal fun decideStartupSubtitleRoute(
    hasEmbeddedSpanish: Boolean,
    autoSyncAvailable: Boolean,
    noEmbeddedTextTracks: Boolean,
): StartupSubtitleRoute = when {
    hasEmbeddedSpanish -> StartupSubtitleRoute.NONE
    !autoSyncAvailable || noEmbeddedTextTracks -> StartupSubtitleRoute.COMMUNITY
    else -> StartupSubtitleRoute.OPEN_SUBTITLES_FIRST
}

internal const val OPEN_SUBTITLES_FIRST_BUDGET_MS = 20_000L
