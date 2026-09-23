package com.nuvio.app.features.streams

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.player.agentqa.AgentQa

object StreamAutoPlaySelector {

    fun orderAddonStreams(
        groups: List<AddonStreamGroup>,
        installedOrder: List<String>,
    ): List<AddonStreamGroup> {
        if (groups.isEmpty()) return groups

        val addonRankByName = HashMap<String, Int>(installedOrder.size)
        installedOrder.forEachIndexed { index, addonName ->
            if (addonName !in addonRankByName) {
                addonRankByName[addonName] = index
            }
        }

        val (directDebridEntries, remainingEntries) = groups.partition { group ->
            group.addonId.startsWith("debrid:") ||
                group.streams.any { stream -> stream.isAddonDebridCandidate && stream.isDirectDebridStream }
        }
        if (installedOrder.isEmpty()) return directDebridEntries + remainingEntries

        val (addonEntries, pluginEntries) = remainingEntries.partition { group ->
            group.addonName in addonRankByName
        }
        val orderedAddons = addonEntries.sortedBy { group ->
            addonRankByName.getValue(group.addonName)
        }
        return directDebridEntries + orderedAddons + pluginEntries
    }

    fun selectAutoPlayStream(
        streams: List<StreamItem>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        debridEnabled: Boolean = true,
        activeResolverProviderId: String? = null,
    ): StreamItem? =
        evaluateAutoPlayStream(
            streams = streams,
            mode = mode,
            regexPattern = regexPattern,
            source = source,
            installedAddonNames = installedAddonNames,
            selectedAddons = selectedAddons,
            selectedPlugins = selectedPlugins,
            preferredBingeGroup = preferredBingeGroup,
            preferBingeGroupInSelection = preferBingeGroupInSelection,
            bingeGroupOnly = bingeGroupOnly,
            debridEnabled = debridEnabled,
            activeResolverProviderId = activeResolverProviderId,
        ).stream

    fun evaluateAutoPlayStream(
        streams: List<StreamItem>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        debridEnabled: Boolean = true,
        activeResolverProviderId: String? = null,
    ): StreamAutoPlayEvaluation {
        if (streams.isEmpty()) return StreamAutoPlayEvaluation()

        val sourceScopedStreams = when (source) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> streams.filter { it.addonName !in installedAddonNames }
        }
        val candidateStreams = sourceScopedStreams.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            if (isAddonStream) {
                selectedAddons.isEmpty() || stream.addonName in selectedAddons
            } else {
                selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
        if (candidateStreams.isEmpty()) return StreamAutoPlayEvaluation()
        if (mode == StreamAutoPlayMode.MANUAL && !bingeGroupOnly) {
            return StreamAutoPlayEvaluation()
        }

        val targetBingeGroup = preferredBingeGroup?.trim().orEmpty()
        val bingeGroupCandidates = if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            candidateStreams.filter { stream -> stream.behaviorHints.bingeGroup == targetBingeGroup }
        } else {
            emptyList()
        }
        val preferredReadyStream = bingeGroupCandidates.firstOrNull { stream ->
            stream.isAutoPlayable(debridEnabled, activeResolverProviderId)
        }
        if (bingeGroupOnly) {
            val readyStreams = preferredReadyStream?.let(::listOf).orEmpty()
            return StreamAutoPlayEvaluation(
                stream = preferredReadyStream,
                readyStreams = readyStreams,
                hasPendingDebridCandidate = preferredReadyStream == null &&
                    bingeGroupCandidates.any {
                        it.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
                    },
            )
        }
        if (mode == StreamAutoPlayMode.MANUAL) {
            return StreamAutoPlayEvaluation()
        }
        val preferredStream = if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            candidateStreams.firstOrNull { stream ->
                stream.behaviorHints.bingeGroup == targetBingeGroup &&
                    stream.isAutoPlayable(debridEnabled, activeResolverProviderId)
            }
        } else {
            null
        }
        val matchingStreams = when (mode) {
            StreamAutoPlayMode.MANUAL -> emptyList()
            StreamAutoPlayMode.FIRST_STREAM -> candidateStreams
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()

                val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                    ?: return StreamAutoPlayEvaluation()

                val exclusionMatches = Regex("\\(\\?![^)]*?\\(([^)]+)\\)").findAll(pattern)

                val exclusionWords = exclusionMatches
                    .flatMap { match -> match.groupValues[1].split("|") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                val excludeRegex = if (exclusionWords.isNotEmpty()) {
                    Regex(
                        "\\b(${exclusionWords.joinToString("|") { Regex.escape(it) }})\\b",
                        RegexOption.IGNORE_CASE,
                    )
                } else null

                candidateStreams.filter { stream ->
                    val url = stream.playableDirectUrl.orEmpty()

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.streamLabel).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(url)
                    }

                    if (!userRegex.containsMatchIn(searchableText)) return@filter false

                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@filter false
                    }

                    true
                }
            }
        }
        if (matchingStreams.isEmpty() && preferredStream == null) return StreamAutoPlayEvaluation()

        val readyStreams = buildList {
            preferredStream?.let(::add)
            matchingStreams
                .filter { it.isAutoPlayable(debridEnabled, activeResolverProviderId) }
                .filterNot { it == preferredStream }
                .forEach(::add)
        }
        val selected = readyStreams.firstOrNull()
        if (selected != null) {
            return StreamAutoPlayEvaluation(
                stream = selected,
                readyStreams = readyStreams,
            )
        }

        return StreamAutoPlayEvaluation(
            readyStreams = readyStreams,
            hasPendingDebridCandidate = matchingStreams.any {
                it.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
            },
        )
    }

    private fun StreamItem.isAutoPlayable(
        debridEnabled: Boolean,
        activeResolverProviderId: String?,
    ): Boolean =
        playableDirectUrl != null ||
            (
                AppFeaturePolicy.p2pEnabled &&
                    needsLocalDebridResolve &&
                    p2pInfoHash != null &&
                    !isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
            ) ||
            (debridEnabled && isAddonDebridCandidate && isReadyDebridAutoPlay(activeResolverProviderId))

    private fun StreamItem.isReadyDebridAutoPlay(activeResolverProviderId: String?): Boolean =
        when {
            isDirectDebridStream -> clientResolve?.service.matchesResolver(activeResolverProviderId)
            isCachedDebridTorrentStream -> debridCacheStatus?.providerId.matchesResolver(activeResolverProviderId)
            else -> false
        }

    private fun StreamItem.isPendingDebridAutoPlay(
        debridEnabled: Boolean,
        activeResolverProviderId: String?,
    ): Boolean {
        if (!debridEnabled || !isInstalledAddonStream || !needsLocalDebridResolve) return false
        if (!debridCacheStatus?.providerId.matchesResolver(activeResolverProviderId)) return false
        val state = debridCacheStatus?.state
        return state == null || state == StreamDebridCacheState.CHECKING
    }

    private fun String?.matchesResolver(activeResolverProviderId: String?): Boolean {
        val active = activeResolverProviderId?.trim().orEmpty()
        return active.isBlank() || this == null || equals(active, ignoreCase = true)
    }

    private fun orderStreamsForAgentQa(streams: List<StreamItem>): List<StreamItem> {
        if (streams.size <= 1) return streams
        val ranked = streams.map { stream -> stream to parseStreamSizeBytes(stream) }
        val compact = ranked
            .filter { (_, bytes) -> bytes != null && bytes in AGENT_QA_MIN_BYTES..AGENT_QA_MAX_BYTES }
            .sortedWith(
                compareByDescending<Pair<StreamItem, Long?>> { (stream, _) ->
                    agentQaLikelyHasEmbeddedReference(stream)
                }.thenBy { it.second },
            )
            .map { it.first }
        if (compact.isNotEmpty()) {
            val rest = streams.filterNot { candidate -> compact.any { it === candidate } }
            return compact + rest
        }
        val known = ranked.filter { it.second != null }.sortedBy { it.second }.map { it.first }
        val unknown = ranked.filter { it.second == null }.map { it.first }
        return if (known.isNotEmpty()) known + unknown else streams
    }

    fun identityMatchesRequestedEpisode(
        season: Int?,
        episode: Int?,
        identityFilename: String?,
        identitySizeBytes: Long? = null,
        advertisedSizeBytes: Long? = null,
    ): Boolean {
        if (season == null || episode == null) return true
        val parsed = parseSeasonEpisode(identityFilename)
        if (parsed != null) {
            return parsed.first == season && parsed.second == episode
        }
        if (identitySizeBytes != null && advertisedSizeBytes != null && advertisedSizeBytes > 0L) {
            val inflated = identitySizeBytes > advertisedSizeBytes * 2L &&
                identitySizeBytes - advertisedSizeBytes > 80L * 1024 * 1024
            if (inflated) return false
        }
        return true
    }

    internal fun parseSeasonEpisode(filename: String?): Pair<Int, Int>? {
        val text = filename?.trim().orEmpty()
        if (text.isEmpty()) return null
        SEASON_EPISODE_IN_NAME.find(text)?.let { match ->
            val season = match.groupValues[1].toIntOrNull() ?: return@let
            val episode = match.groupValues[2].toIntOrNull() ?: return@let
            return season to episode
        }
        SEASON_X_EPISODE_IN_NAME.find(text)?.let { match ->
            val season = match.groupValues[2].toIntOrNull() ?: return@let
            val episode = match.groupValues[3].toIntOrNull() ?: return@let
            return season to episode
        }
        return null
    }

    private fun parseStreamSizeBytes(stream: StreamItem): Long? {
        stream.behaviorHints.videoSize?.takeIf { it > 0L }?.let { return it }
        val text = listOfNotNull(stream.name, stream.title, stream.description, stream.streamLabel)
            .joinToString(" ")
        val match = AGENT_QA_SIZE_IN_LABEL.find(text) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].uppercase()) {
            "TB" -> 1024.0 * 1024 * 1024 * 1024
            "GB" -> 1024.0 * 1024 * 1024
            else -> 1024.0 * 1024
        }
        return (amount * multiplier).toLong()
    }

    private fun agentQaLikelyHasEmbeddedReference(stream: StreamItem): Boolean {
        val text = listOfNotNull(
            stream.behaviorHints.filename,
            stream.name,
            stream.title,
            stream.description,
            stream.streamLabel,
        ).joinToString(" ")
        return EMBEDDED_REFERENCE_HINT.containsMatchIn(text)
    }

    private val AGENT_QA_SIZE_IN_LABEL = Regex("""(\d+(?:\.\d+)?)\s*(TB|GB|MB)""", RegexOption.IGNORE_CASE)
    private val SEASON_EPISODE_IN_NAME = Regex("""[sS](\d{1,2})[eE](\d{1,3})""")
    private val SEASON_X_EPISODE_IN_NAME = Regex("""(^|[^0-9])(\d{1,2})[xX](\d{1,3})([^0-9]|$)""")
    private val EMBEDDED_REFERENCE_HINT = Regex("""REMUX|BluRay|BDRemux|BDRip""", RegexOption.IGNORE_CASE)
    private const val AGENT_QA_MIN_BYTES = 80L * 1024 * 1024
    private const val AGENT_QA_MAX_BYTES = 12L * 1024 * 1024 * 1024
}

data class StreamAutoPlayEvaluation(
    val stream: StreamItem? = null,
    val readyStreams: List<StreamItem> = emptyList(),
    val hasPendingDebridCandidate: Boolean = false,
)
