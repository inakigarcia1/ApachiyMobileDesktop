package com.nuvio.app.features.player.skip

import com.nuvio.app.features.details.MetaVideo
import kotlin.math.roundToInt

object PlayerNextEpisodeRules {

    fun resolveNextEpisode(
        videos: List<MetaVideo>,
        currentSeason: Int?,
        currentEpisode: Int?,
    ): MetaVideo? {
        if (currentSeason == null || currentEpisode == null) return null
        val sortedEpisodes = videos
            .filter { it.season != null && it.episode != null }
            .sortedWith(
                compareBy<MetaVideo> { it.season ?: Int.MAX_VALUE }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
            )

        val currentIndex = sortedEpisodes.indexOfFirst {
            it.season == currentSeason && it.episode == currentEpisode
        }
        if (currentIndex < 0) return null
        return sortedEpisodes.getOrNull(currentIndex + 1)
    }

    fun shouldShowNextEpisodeCard(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float,
        useAdaptiveLead: Boolean = false,
        anime: Boolean = false,
    ): Boolean {
        val promptAtMs = nextEpisodePromptPositionMs(
            durationMs = durationMs,
            skipIntervals = skipIntervals,
            thresholdMode = thresholdMode,
            thresholdPercent = thresholdPercent,
            thresholdMinutesBeforeEnd = thresholdMinutesBeforeEnd,
            useAdaptiveLead = useAdaptiveLead,
            anime = anime,
        ) ?: return false
        return positionMs >= promptAtMs
    }

    fun shouldPreloadNextEpisodeSources(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float,
        useAdaptiveLead: Boolean = false,
        anime: Boolean = false,
    ): Boolean {
        val promptAtMs = nextEpisodePromptPositionMs(
            durationMs = durationMs,
            skipIntervals = skipIntervals,
            thresholdMode = thresholdMode,
            thresholdPercent = thresholdPercent,
            thresholdMinutesBeforeEnd = thresholdMinutesBeforeEnd,
            useAdaptiveLead = useAdaptiveLead,
            anime = anime,
        ) ?: return false
        return positionMs >= (promptAtMs - PRELOAD_LEAD_MS).coerceAtLeast(0L)
    }

    fun nextEpisodePromptPositionMs(
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float,
        useAdaptiveLead: Boolean = false,
        anime: Boolean = false,
    ): Long? {
        if (durationMs <= 0L) return null
        val userThresholdMs = thresholdWindowFromEndMs(
            durationMs = durationMs,
            thresholdMode = thresholdMode,
            thresholdPercent = thresholdPercent,
            thresholdMinutesBeforeEnd = thresholdMinutesBeforeEnd,
            useAdaptiveLead = useAdaptiveLead,
            anime = anime,
        )
        val outroSegments = skipIntervals.filter { it.type in OUTRO_SEGMENT_TYPES }
        if (outroSegments.isNotEmpty()) {
            val latestOutroEndMs = (outroSegments.maxOf { it.endTime } * 1_000.0).toLong()
            val postOutroGapMs = durationMs - latestOutroEndMs
            if (postOutroGapMs <= userThresholdMs) {
                return (outroSegments.minOf { it.startTime } * 1_000.0).toLong()
            }
        }
        return (durationMs - userThresholdMs).coerceAtLeast(0L)
    }

    private fun thresholdWindowFromEndMs(
        durationMs: Long,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float,
        useAdaptiveLead: Boolean,
        anime: Boolean,
    ): Long {
        if (useAdaptiveLead) return adaptiveLeadMs(durationMs, anime)
        return when (thresholdMode) {
            NextEpisodeThresholdMode.PERCENTAGE -> {
                val steps = (thresholdPercent.coerceIn(THRESHOLD_PERCENT_MIN, THRESHOLD_PERCENT_MAX) * 2f).roundToInt()
                durationMs * (200 - steps) / 200
            }
            NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                (clampedMinutes * 60_000f).toLong()
            }
        }
    }

    fun adaptiveLeadMs(durationMs: Long, anime: Boolean): Long {
        if (durationMs <= 0L) return 0L
        val minutes = durationMs / 60_000.0
        val row = when {
            minutes >= 120.0 -> ADAPTIVE_LEAD_ROWS.last()
            minutes <= 20.0 -> ADAPTIVE_LEAD_ROWS.first()
            else -> ADAPTIVE_LEAD_ROWS.minWith(
                compareBy<Pair<Int, Pair<Int, Int>>> { kotlin.math.abs(it.first - minutes) }
                    .thenBy { it.first },
            )
        }
        val seconds = if (anime) row.second.second else row.second.first
        return seconds * 1_000L
    }

    fun isAnimePlayback(
        contentType: String?,
        ids: List<String?>,
        genres: List<String>,
        country: String?,
    ): Boolean {
        if (contentType.equals("anime", ignoreCase = true)) return true
        if (ids.any { id ->
                val value = id?.trim().orEmpty()
                value.startsWith("kitsu:", ignoreCase = true) ||
                    value.startsWith("mal:", ignoreCase = true) ||
                    value.startsWith("anilist:", ignoreCase = true)
            }
        ) return true
        if (genres.any { it.equals("anime", ignoreCase = true) }) return true
        return genres.any { it.equals("animation", ignoreCase = true) } &&
            country?.contains("Japan", ignoreCase = true) == true
    }

    private val ADAPTIVE_LEAD_ROWS = listOf(
        20 to (12 to 75),
        23 to (12 to 75),
        25 to (15 to 75),
        30 to (18 to 75),
        35 to (25 to 75),
        40 to (30 to 75),
        45 to (45 to 90),
        50 to (60 to 90),
        55 to (75 to 90),
        60 to (105 to 105),
        90 to (120 to 120),
        105 to (135 to 135),
        120 to (150 to 150),
    )

    fun hasEpisodeAired(raw: String?): Boolean {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        val dateStr = when {
            value.length >= 10 -> value.substring(0, 10)
            else -> return true
        }
        // Parse YYYY-MM-DD
        val parts = dateStr.split("-")
        if (parts.size != 3) return true
        val year = parts[0].toIntOrNull() ?: return true
        val month = parts[1].toIntOrNull() ?: return true
        val day = parts[2].toIntOrNull() ?: return true

        val today = currentDateComponents()
        return compareDate(year, month, day, today.year, today.month, today.day) <= 0
    }

    private fun compareDate(
        y1: Int, m1: Int, d1: Int,
        y2: Int, m2: Int, d2: Int,
    ): Int {
        if (y1 != y2) return y1.compareTo(y2)
        if (m1 != m2) return m1.compareTo(m2)
        return d1.compareTo(d2)
    }

    val OUTRO_SEGMENT_TYPES = setOf("outro", "ed", "mixed-ed")

    const val THRESHOLD_PERCENT_MIN = 85f
    const val THRESHOLD_PERCENT_MAX = 100f
    const val THRESHOLD_PERCENT_DEFAULT = 96f
    const val PRELOAD_LEAD_MS = 15_000L
}

internal expect fun currentDateComponents(): DateComponents

data class DateComponents(val year: Int, val month: Int, val day: Int)
