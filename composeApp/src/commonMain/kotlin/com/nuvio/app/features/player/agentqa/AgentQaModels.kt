package com.nuvio.app.features.player.agentqa

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class AgentQaActiveCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

@Serializable
data class AgentQaSnapshot(
    val updatedAtEpochMs: Long,
    val title: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val parentMetaId: String = "",
    val parentMetaType: String = "",
    val videoId: String = "",
    val pipeline: String = "idle",
    val skipReason: String? = null,
    val hasEmbeddedSpanish: Boolean = false,
    val referenceUsable: Boolean = false,
    val referenceCueCount: Int = 0,
    val cacheKey: String? = null,
    val selectedAddonSubtitleId: String? = null,
    val selectedAddonLanguage: String? = null,
    val selectedAddonDisplay: String? = null,
    val useCustomSubtitles: Boolean = false,
    val subtitleDelayMs: Int = 0,
    val subtitlePipelineDone: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val activeCue: AgentQaActiveCue? = null,
    val cueCoversClock: Boolean = false,
    val syncScorePass: Boolean? = null,
    val syncMedianOffsetMs: Long? = null,
    val syncResidualP80Ms: Long? = null,
    val syncReason: String? = null,
)

@Serializable
data class AgentQaCommand(
    val id: String = "",
    val action: String = "",
    val positionMs: Long? = null,
    val metaType: String? = null,
    val metaId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val url: String? = null,
)

@Serializable
data class AgentQaCommandAck(
    val id: String,
    val action: String,
    val result: String,
    val updatedAtEpochMs: Long,
)

@Serializable
data class AgentQaAccount(
    val email: String,
    val password: String,
)

@Serializable
data class AgentQaSecretsFile(
    val accounts: List<AgentQaAccount> = emptyList(),
)

@Serializable
data class AgentQaAppState(
    val updatedAtEpochMs: Long,
    val gate: String,
    val auth: String,
    val email: String? = null,
    val autoLogin: String = "idle",
)

data class AgentQaSyncScore(
    val pass: Boolean,
    val medianOffsetMs: Long?,
    val residualP80Ms: Long?,
    val referenceCueCount: Int,
    val appliedCueCount: Int,
    val reason: String,
)

object AgentQaSyncScorer {
    private const val MIN_CUES = 16
    private const val PASS_RESIDUAL_P80_MS = 2_000L

    fun score(
        reference: List<SubtitleSyncCue>,
        applied: List<SubtitleSyncCue>,
    ): AgentQaSyncScore {
        if (applied.size < MIN_CUES) {
            return AgentQaSyncScore(false, null, null, reference.size, applied.size, "applied_too_few")
        }
        if (reference.size < MIN_CUES) {
            return AgentQaSyncScore(false, null, null, reference.size, applied.size, "reference_too_few")
        }
        val appliedStarts = applied.map { it.startTimeMs }.sorted()
        val offsets = reference.map { cue ->
            nearestStart(appliedStarts, cue.startTimeMs) - cue.startTimeMs
        }.sorted()
        val median = offsets[offsets.size / 2]
        val residuals = offsets.map { abs(it - median) }.sorted()
        val p80 = residuals[((residuals.size - 1) * 4) / 5]
        val pass = p80 <= PASS_RESIDUAL_P80_MS
        return AgentQaSyncScore(
            pass = pass,
            medianOffsetMs = median,
            residualP80Ms = p80,
            referenceCueCount = reference.size,
            appliedCueCount = applied.size,
            reason = if (pass) "aligned" else "residual_too_high",
        )
    }

    private fun nearestStart(sortedStarts: List<Long>, target: Long): Long {
        var low = 0
        var high = sortedStarts.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = sortedStarts[mid]
            when {
                value < target -> low = mid + 1
                value > target -> high = mid - 1
                else -> return value
            }
        }
        val after = sortedStarts.getOrNull(low)
        val before = sortedStarts.getOrNull(high)
        return when {
            after == null -> before ?: target
            before == null -> after
            abs(after - target) < abs(before - target) -> after
            else -> before
        }
    }

    fun activeCueAt(cues: List<SubtitleSyncCue>, positionMs: Long, delayMs: Int): SubtitleSyncCue? {
        val clock = positionMs - delayMs
        return cues.firstOrNull { clock in it.startTimeMs..it.endTimeMs }
            ?: cues.minByOrNull { cue ->
                val mid = (cue.startTimeMs + cue.endTimeMs) / 2
                abs(mid - clock)
            }?.takeIf { abs(((it.startTimeMs + it.endTimeMs) / 2) - clock) <= 4_000L }
    }
}
