package com.nuvio.app.features.player.embedded

import com.nuvio.app.features.addons.httpGetBytesWithHeaders
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.addons.readLocalFilePrefix
import com.nuvio.app.features.player.agentqa.AgentQa
import kotlin.time.TimeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

internal object EmbeddedSubtitleExtractor {
    private const val PREFIX_BYTES = 2 * 1024 * 1024
    private const val TRACKS_BYTES = 512 * 1024
    private const val CUES_HEADER_BYTES = 64
    private const val MAX_CUES_BYTES = 16 * 1024 * 1024
    private const val BLOCK_BYTES = 16 * 1024
    private const val MAX_CLUSTER_WINDOW_BYTES = 2 * 1024 * 1024
    private const val CLUSTER_FETCH_CONCURRENCY = 2
    private const val CLUSTER_SAMPLES = 28
    private const val CLUSTER_SAMPLE_BYTES = 320 * 1024
    private const val CLUSTER_SAMPLE_TARGET_CUES = 24
    private const val BYTE_SAMPLES = 26
    private const val RANGE_READ_TIMEOUT_MS = 10_000L
    private const val HARVEST_BUDGET_MS = 30_000L
    private const val DIALOGUE_TIMING_TIMEOUT_MS = 12_000L
    private val directMediaUrls = mutableMapOf<String, String>()
    private val directMediaUrlLock = Any()

    suspend fun probeFileIdentity(
        sourceUrl: String,
        headers: Map<String, String>,
        declaredSize: Long? = null,
    ): MediaFileIdentity {
        val trimmed = sourceUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return MediaFileIdentity(sizeBytes = declaredSize)
        }
        val probe = runCatching {
            httpRequestRaw(
                "GET",
                trimmed,
                headers + mapOf(
                    "Range" to "bytes=0-0",
                    "Connection" to "close",
                ),
                "",
                true,
                8,
            )
        }.getOrNull()
        val filename = parseContentDispositionFilename(probe?.headers?.get("content-disposition"))
        val sizeBytes = parseHttpSizeBytes(probe?.headers.orEmpty()) ?: declaredSize
        return MediaFileIdentity(filename = filename, sizeBytes = sizeBytes)
    }

    suspend fun extract(
        sourceUrl: String,
        headers: Map<String, String>,
        videoSize: Long? = null,
    ): EmbeddedExtractResult? {
        val prefix = readMediaRange(sourceUrl, headers, 0L, PREFIX_BYTES)
        if (prefix == null) {
            return EmbeddedExtractResult(hasEmbeddedSpanish = false, reference = null, mediaReady = false)
        }
        val isMkv = looksLikeMkv(prefix)
        val isMp4 = looksLikeMp4(prefix)
        val layout = if (isMkv) MkvTextSubtitleParser.parseLayout(prefix) else null
        var tracks = when {
            layout != null -> layout.tracks
            isMp4 -> Mp4TextSubtitleParser.parse(prefix)
            else -> emptyList()
        }
        if (isMkv && layout?.tracksOffset != null) {
            val tracksBytes = readMediaRange(sourceUrl, headers, layout.tracksOffset, TRACKS_BYTES)
            if (tracksBytes != null) {
                tracks = mergeTrackMetadata(tracks, MkvTextSubtitleParser.parseTracksElement(tracksBytes))
            }
        }
        if (isMkv && layout != null) {
            tracks = harvestCompleteSrt(sourceUrl, headers, videoSize, prefix, layout, tracks)
        }
        if (tracks.isEmpty()) {
            val incomplete = !isMkv && prefix.size < PREFIX_BYTES
            return if (incomplete) {
                EmbeddedExtractResult(hasEmbeddedSpanish = false, reference = null, mediaReady = false)
            } else {
                null
            }
        }
        val hasSpanish = hasEmbeddedSpanishTextTrack(tracks)
        if (hasSpanish) {
            return EmbeddedExtractResult(hasEmbeddedSpanish = true, reference = null)
        }
        val chosen = selectEmbeddedReferenceTrack(tracks)
        val reference = chosen?.takeIf(::isUsableEmbeddedReference)?.toReference()
        return EmbeddedExtractResult(false, reference)
    }

    private fun mergeTrackMetadata(
        seed: List<EmbeddedTextTrack>,
        header: List<EmbeddedTextTrack>,
    ): List<EmbeddedTextTrack> {
        if (header.isEmpty()) return seed
        val merged = seed.associateBy { it.trackNumber }.toMutableMap()
        for (track in header) {
            val existing = merged[track.trackNumber]
            merged[track.trackNumber] = if (existing == null) {
                track
            } else {
                existing.copy(
                    language = existing.language?.takeIf { it.isNotBlank() } ?: track.language,
                    name = existing.name?.takeIf { it.isNotBlank() } ?: track.name,
                    forced = existing.forced || track.forced,
                )
            }
        }
        return merged.values.toList()
    }

    private suspend fun harvestCompleteSrt(
        sourceUrl: String,
        headers: Map<String, String>,
        videoSize: Long?,
        prefix: ByteArray,
        layout: MkvLayout,
        seedTracks: List<EmbeddedTextTrack>,
    ): List<EmbeddedTextTrack> {
        val trackNumber = firstSrtTrackNumber(seedTracks)
        if (trackNumber == null) {
            return seedTracks
        }
        var cuesOffset = layout.cuesOffset
        var cuesBytes: ByteArray? = null
        var cueRefs = emptyList<MkvCueRef>()
        val offsets = mutableListOf<Pair<Long, Int?>>()
        if (cuesOffset != null) {
            offsets += cuesOffset to null
        } else if (videoSize != null) {
            val candidates = findCuesCandidatesInTail(sourceUrl, headers, videoSize)
            offsets += candidates.map { (offset, total) -> offset to total }
        }
        if (offsets.isEmpty()) {
            return seedTracks
        }
        var allRefs = emptyList<MkvCueRef>()
        for ((offset, knownTotal) in offsets) {
            val bytes = readFullCues(sourceUrl, headers, prefix, offset, knownTotal) ?: continue
            val parsed = MkvTextSubtitleParser.parseCues(bytes)
            if (parsed.size <= allRefs.size) continue
            cuesOffset = offset
            cuesBytes = bytes
            allRefs = parsed
            cueRefs = parsed
                .filter { ref -> ref.trackNumber == trackNumber && ref.relativePosition != null }
                .sortedBy { it.timeTicks }
        }
        val resolvedBytes = cuesBytes
        if (resolvedBytes == null || allRefs.isEmpty()) {
            return harvestByByteSampling(
                sourceUrl = sourceUrl,
                headers = headers,
                videoSize = videoSize,
                layout = layout,
                seedTracks = seedTracks,
                trackNumber = trackNumber,
            )
        }
        var tracks = seedTracks
        val fetches = clusterFetches(cueRefs)
        coroutineScope {
            for (chunk in fetches.chunked(CLUSTER_FETCH_CONCURRENCY)) {
                val windows = chunk.map { fetch ->
                    async {
                        val clusterFile = layout.segmentDataOffset + fetch.clusterPosition
                        val minRel = fetch.refs.minOf { it.relativePosition ?: 0L }
                        val maxRel = fetch.refs.maxOf { it.relativePosition ?: 0L }
                        val windowStart = (clusterFile + minRel - 4L).coerceAtLeast(clusterFile)
                        val windowBytes = (maxRel - minRel + BLOCK_BYTES + 8L)
                            .toInt()
                            .coerceIn(BLOCK_BYTES, MAX_CLUSTER_WINDOW_BYTES)
                        if (videoSize != null && windowStart >= videoSize) return@async null
                        val window = readMediaRange(sourceUrl, headers, windowStart, windowBytes)
                            ?: return@async null
                        HarvestWindow(windowStart, fetch.clusterPosition, fetch.refs, window)
                    }
                }.awaitAll()
                for (harvest in windows) {
                    if (harvest == null) continue
                    for (ref in harvest.refs) {
                        val relative = ref.relativePosition ?: continue
                        val blockFile = layout.segmentDataOffset + harvest.clusterPosition + relative
                        val absStart = (blockFile - 4L).coerceAtLeast(layout.segmentDataOffset + harvest.clusterPosition)
                        val local = (absStart - harvest.windowStart).toInt()
                        if (local < 0 || local >= harvest.window.size) continue
                        val sliceEnd = (local + BLOCK_BYTES).coerceAtMost(harvest.window.size)
                        tracks = MkvTextSubtitleParser.harvestBlockWindow(
                            harvest.window.copyOfRange(local, sliceEnd),
                            tracks,
                            layout.timestampScale,
                            ref.timeTicks,
                        )
                    }
                }
            }
        }
        val harvested = tracks.firstOrNull { it.trackNumber == trackNumber }?.cues?.size ?: 0
        if (harvested < CLUSTER_SAMPLE_TARGET_CUES) {
            tracks = harvestSampledClusters(
                sourceUrl = sourceUrl,
                headers = headers,
                videoSize = videoSize,
                layout = layout,
                seedTracks = tracks,
                allRefs = allRefs,
                trackNumber = trackNumber,
            )
        }
        if (tracks.firstOrNull { it.trackNumber == trackNumber }?.let { !isUsableEmbeddedReference(it) } != false) {
            tracks = harvestByByteSampling(
                sourceUrl = sourceUrl,
                headers = headers,
                videoSize = videoSize,
                layout = layout,
                seedTracks = tracks,
                trackNumber = trackNumber,
            )
        }
        val finalCues = tracks.firstOrNull { it.trackNumber == trackNumber }?.cues?.size ?: 0
        if (AgentQa.enabled) {
            AgentQa.event(
                "embedded_harvest",
                "track=$trackNumber refs=${cueRefs.size} clusters=${fetches.size} cues=$finalCues",
            )
        }
        return tracks
    }

    /**
     * Last resort when the Cues index is missing or unreachable: walk evenly spaced byte
     * offsets, find the first Cluster header in each window and harvest its text blocks.
     */
    private suspend fun harvestByByteSampling(
        sourceUrl: String,
        headers: Map<String, String>,
        videoSize: Long?,
        layout: MkvLayout,
        seedTracks: List<EmbeddedTextTrack>,
        trackNumber: Long,
    ): List<EmbeddedTextTrack> {
        val span = (videoSize ?: 0L) - layout.segmentDataOffset
        if (span <= CLUSTER_SAMPLE_BYTES) {
            return seedTracks
        }
        val offsets = (0 until BYTE_SAMPLES).map { index ->
            layout.segmentDataOffset + span * index / BYTE_SAMPLES
        }
        var tracks = seedTracks
        var read = 0
        var failed = 0
        var noCluster = 0
        val budget = TimeSource.Monotonic.markNow()
        coroutineScope {
            for (chunk in offsets.chunked(CLUSTER_FETCH_CONCURRENCY)) {
                if (budget.elapsedNow().inWholeMilliseconds >= HARVEST_BUDGET_MS) break
                val windows = chunk.map { start ->
                    async { readMediaRange(sourceUrl, headers, start, CLUSTER_SAMPLE_BYTES) }
                }.awaitAll()
                for (window in windows) {
                    if (window == null) {
                        failed += 1
                        continue
                    }
                    read += 1
                    val at = indexOfClusterId(window)
                    if (at < 0) {
                        noCluster += 1
                        continue
                    }
                    tracks = MkvTextSubtitleParser.harvestClusterWindow(
                        window.copyOfRange(at, window.size),
                        tracks,
                        layout.timestampScale,
                    )
                }
                val current = tracks.firstOrNull { it.trackNumber == trackNumber }
                if (current != null && isUsableEmbeddedReference(current)) break
            }
        }
        return tracks
    }

    private fun indexOfClusterId(data: ByteArray): Int {
        var index = 0
        while (index <= data.size - 4) {
            if ((data[index].toInt() and 0xFF) == 0x1F &&
                (data[index + 1].toInt() and 0xFF) == 0x43 &&
                (data[index + 2].toInt() and 0xFF) == 0xB6 &&
                (data[index + 3].toInt() and 0xFF) == 0x75
            ) {
                return index
            }
            index += 1
        }
        return -1
    }

    private suspend fun harvestSampledClusters(
        sourceUrl: String,
        headers: Map<String, String>,
        videoSize: Long?,
        layout: MkvLayout,
        seedTracks: List<EmbeddedTextTrack>,
        allRefs: List<MkvCueRef>,
        trackNumber: Long,
    ): List<EmbeddedTextTrack> {
        val clusterPositions = allRefs.map { it.clusterPosition }.distinct().sorted()
        if (clusterPositions.isEmpty()) return seedTracks
        val sampled = pickEvenIndices(clusterPositions.size, CLUSTER_SAMPLES)
            .map { index -> clusterPositions[index] }
        var tracks = seedTracks
        var failed = 0
        var read = 0
        val budget = TimeSource.Monotonic.markNow()
        coroutineScope {
            for (chunk in sampled.chunked(CLUSTER_FETCH_CONCURRENCY)) {
                if (budget.elapsedNow().inWholeMilliseconds >= HARVEST_BUDGET_MS) break
                val windows = chunk.map { clusterPosition ->
                    async {
                        val start = layout.segmentDataOffset + clusterPosition
                        if (videoSize != null && start >= videoSize) return@async null
                        readMediaRange(sourceUrl, headers, start, CLUSTER_SAMPLE_BYTES)
                    }
                }.awaitAll()
                for (window in windows) {
                    if (window == null) {
                        failed += 1
                        continue
                    }
                    read += 1
                    tracks = MkvTextSubtitleParser.harvestClusterWindow(
                        window,
                        tracks,
                        layout.timestampScale,
                    )
                }
                val current = tracks.firstOrNull { it.trackNumber == trackNumber }
                if (current != null && isUsableEmbeddedReference(current)) break
            }
        }
        return tracks
    }

    private fun clusterFetches(refs: List<MkvCueRef>): List<ClusterFetch> {
        val fetches = mutableListOf<ClusterFetch>()
        for ((clusterPosition, clusterRefs) in refs.groupBy { it.clusterPosition }) {
            val sorted = clusterRefs.sortedBy { it.relativePosition ?: 0L }
            var bucket = mutableListOf<MkvCueRef>()
            var bucketMin = 0L
            for (ref in sorted) {
                val relative = ref.relativePosition ?: continue
                if (bucket.isEmpty()) {
                    bucket.add(ref)
                    bucketMin = relative
                    continue
                }
                val span = relative - bucketMin + BLOCK_BYTES + 8L
                if (span > MAX_CLUSTER_WINDOW_BYTES) {
                    fetches += ClusterFetch(clusterPosition, bucket)
                    bucket = mutableListOf(ref)
                    bucketMin = relative
                } else {
                    bucket.add(ref)
                }
            }
            if (bucket.isNotEmpty()) fetches += ClusterFetch(clusterPosition, bucket)
        }
        return fetches
    }

    private data class ClusterFetch(
        val clusterPosition: Long,
        val refs: List<MkvCueRef>,
    )

    private data class HarvestWindow(
        val windowStart: Long,
        val clusterPosition: Long,
        val refs: List<MkvCueRef>,
        val window: ByteArray,
    )

    private fun firstSrtTrackNumber(tracks: List<EmbeddedTextTrack>): Long? =
        tracks.firstOrNull { track ->
            track.codec == EmbeddedTextCodec.SubRip &&
                track.trackNumber > 0L &&
                !isForcedTextTrack(track.forced, track.language, track.name) &&
                !hasEmbeddedSpanishTextTrack(listOf(track))
        }?.trackNumber

    private suspend fun readFullCues(
        sourceUrl: String,
        headers: Map<String, String>,
        prefix: ByteArray,
        cuesOffset: Long,
        knownTotalBytes: Int? = null,
    ): ByteArray? {
        if (knownTotalBytes != null) {
            return readMediaRange(sourceUrl, headers, cuesOffset, knownTotalBytes)
        }
        val headerBytes = if (cuesOffset < prefix.size - 8) {
            val from = cuesOffset.toInt().coerceAtLeast(0)
            val to = (from + CUES_HEADER_BYTES).coerceAtMost(prefix.size)
            prefix.copyOfRange(from, to)
        } else {
            readMediaRange(sourceUrl, headers, cuesOffset, CUES_HEADER_BYTES)
        } ?: return null
        val totalBytes = MkvTextSubtitleParser.cuesElementTotalBytes(headerBytes)
            ?.coerceAtMost(MAX_CUES_BYTES)
            ?: MAX_CUES_BYTES
        if (cuesOffset < prefix.size && cuesOffset.toInt() + totalBytes <= prefix.size) {
            return prefix.copyOfRange(cuesOffset.toInt(), cuesOffset.toInt() + totalBytes)
        }
        return readMediaRange(sourceUrl, headers, cuesOffset, totalBytes)
    }

    private suspend fun findCuesCandidatesInTail(
        sourceUrl: String,
        headers: Map<String, String>,
        videoSize: Long,
    ): List<Pair<Long, Int>> {
        val tailBytes = 2 * 1024 * 1024
        if (videoSize <= 16L) return emptyList()
        val length = minOf(tailBytes.toLong(), videoSize).toInt()
        val start = videoSize - length
        val bytes = readMediaRange(sourceUrl, headers, start, length) ?: return emptyList()
        val found = mutableListOf<Pair<Long, Int>>()
        var index = 0
        while (index <= bytes.size - 4) {
            val isCuesId = (bytes[index].toInt() and 0xFF) == 0x1C &&
                (bytes[index + 1].toInt() and 0xFF) == 0x53 &&
                (bytes[index + 2].toInt() and 0xFF) == 0xBB &&
                (bytes[index + 3].toInt() and 0xFF) == 0x6B
            if (isCuesId) {
                val sliceEnd = (index + 16).coerceAtMost(bytes.size)
                val total = MkvTextSubtitleParser.cuesElementTotalBytes(bytes.copyOfRange(index, sliceEnd))
                if (total != null && total >= 512) {
                    found += (start + index) to total
                }
            }
            index += 1
        }
        return found.sortedByDescending { it.second }
    }

    private suspend fun readMediaRange(
        sourceUrl: String,
        headers: Map<String, String>,
        startByte: Long,
        maxBytes: Int,
    ): ByteArray? {
        val trimmed = sourceUrl.trim()
        val isLocal = trimmed.startsWith("file:", ignoreCase = true) ||
            (trimmed.startsWith("/") && !trimmed.startsWith("//")) ||
            (
                !trimmed.startsWith("http://", ignoreCase = true) &&
                    !trimmed.startsWith("https://", ignoreCase = true)
                )
        if (isLocal) {
            if (startByte > 0L) return null
            val path = trimmed.removePrefix("file://").removePrefix("file:")
            return readLocalFilePrefix(path, maxBytes)
        }
        val endByte = startByte + maxBytes - 1
        val cachedDirect = synchronized(directMediaUrlLock) { directMediaUrls[trimmed] }
        val requestUrl = cachedDirect ?: trimmed
        val rangeHeaders = headersForMedia(headers, requestUrl != trimmed) + mapOf(
            "Range" to "bytes=$startByte-$endByte",
            "Connection" to "close",
        )
        val loaded = withTimeoutOrNull(RANGE_READ_TIMEOUT_MS) {
            httpGetBytesWithHeaders(requestUrl, rangeHeaders, maxBytes)
        } ?: return null
        if (cachedDirect == null) {
            val direct = directMediaUrl(trimmed, loaded.resolvedUrl)
            if (direct != trimmed) {
                synchronized(directMediaUrlLock) { directMediaUrls[trimmed] = direct }
            }
        }
        return loaded.bytes
    }

    private fun headersForMedia(
        headers: Map<String, String>,
        crossedHost: Boolean,
    ): Map<String, String> {
        if (!crossedHost) return headers
        return headers.filterKeys { name ->
            !name.equals("Authorization", ignoreCase = true) &&
                !name.equals("Cookie", ignoreCase = true) &&
                !name.equals("Proxy-Authorization", ignoreCase = true)
        }
    }

    private fun directMediaUrl(sourceUrl: String, resolvedUrl: String): String {
        if (resolvedUrl.isBlank() || resolvedUrl == sourceUrl) return sourceUrl
        val sourceHost = sourceUrl.substringBefore('?').substringAfter("://", "").substringBefore('/')
        val resolvedHost = resolvedUrl.substringBefore('?').substringAfter("://", "").substringBefore('/')
        if (resolvedHost.isEmpty() || resolvedHost == sourceHost) return sourceUrl
        return resolvedUrl
    }

    suspend fun loadDialogueTiming(
        sourceUrl: String,
        headers: Map<String, String>,
    ): DialogueTimingIndex {
        val trimmed = sourceUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return DialogueTimingIndex()
        }
        return withTimeoutOrNull(DIALOGUE_TIMING_TIMEOUT_MS) {
            val prefix = readMediaRange(trimmed, headers, 0L, PREFIX_BYTES)
                ?: return@withTimeoutOrNull DialogueTimingIndex()
            when {
                looksLikeMkv(prefix) -> loadMkvDialogueTiming(trimmed, headers, prefix)
                looksLikeMp4(prefix) -> loadMp4DialogueTiming(trimmed, headers)
                else -> DialogueTimingIndex()
            }
        } ?: DialogueTimingIndex()
    }

    private suspend fun loadMkvDialogueTiming(
        sourceUrl: String,
        headers: Map<String, String>,
        prefix: ByteArray,
    ): DialogueTimingIndex {
        val layout = MkvTextSubtitleParser.parseLayout(prefix)
        var tracks = layout.tracks
        if (layout.tracksOffset != null) {
            val tracksBytes = readMediaRange(sourceUrl, headers, layout.tracksOffset, TRACKS_BYTES)
            if (tracksBytes != null) {
                tracks = mergeTrackMetadata(tracks, MkvTextSubtitleParser.parseTracksElement(tracksBytes))
            }
        }
        if (tracks.isEmpty()) {
            return DialogueTimingIndex(noSubtitleTracks = layout.tracksOffset != null || prefix.size >= 64)
        }
        if (hasEmbeddedSpanishTextTrack(tracks)) {
            return DialogueTimingIndex(hasEmbeddedSpanish = true, tracks = tracks)
        }
        val cuesOffset = layout.cuesOffset ?: return DialogueTimingIndex(tracks = tracks)
        val cuesBytes = readFullCues(sourceUrl, headers, prefix, cuesOffset, null)
            ?: return DialogueTimingIndex(tracks = tracks)
        val refs = MkvTextSubtitleParser.parseCues(cuesBytes)
        if (refs.isEmpty()) return DialogueTimingIndex(tracks = tracks)
        val scale = layout.timestampScale.coerceAtLeast(1L)
        val timed = tracks.map { track ->
            val starts = refs
                .filter { it.trackNumber == track.trackNumber }
                .map { it.timeTicks * scale / 1_000_000L }
                .sorted()
            val cues = starts.mapIndexed { index, start ->
                val next = starts.getOrNull(index + 1)
                val end = if (next == null) start + 2_000L else minOf(next, start + 4_000L)
                EmbeddedSubtitleCue(start, end.coerceAtLeast(start + 1L), "")
            }
            track.copy(cues = cues, estimatedCueEnds = true)
        }
        return DialogueTimingIndex(tracks = timed)
    }

    private suspend fun loadMp4DialogueTiming(
        sourceUrl: String,
        headers: Map<String, String>,
    ): DialogueTimingIndex {
        val bytes = readMediaRange(sourceUrl, headers, 0L, 24 * 1024 * 1024)
            ?: return DialogueTimingIndex()
        val tracks = Mp4TextSubtitleParser.parseTiming(bytes)
        if (tracks.isEmpty()) return DialogueTimingIndex(noSubtitleTracks = true)
        if (hasEmbeddedSpanishTextTrack(tracks)) {
            return DialogueTimingIndex(hasEmbeddedSpanish = true, tracks = tracks)
        }
        return DialogueTimingIndex(tracks = tracks)
    }

    private fun looksLikeMkv(data: ByteArray): Boolean =
        data.size >= 4 &&
            (data[0].toInt() and 0xFF) == 0x1A &&
            (data[1].toInt() and 0xFF) == 0x45 &&
            (data[2].toInt() and 0xFF) == 0xDF &&
            (data[3].toInt() and 0xFF) == 0xA3

    private fun looksLikeMp4(data: ByteArray): Boolean {
        if (data.size < 8) return false
        val type = data.decodeToString(4, 8)
        return type == "ftyp" || type == "moov" || type == "mdat"
    }
}

internal fun parseContentDispositionFilename(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val rfc5987 = Regex(
        """filename\*\s*=\s*(?:UTF-8|utf-8|ISO-8859-1|iso-8859-1)''([^;]+)""",
        RegexOption.IGNORE_CASE,
    ).find(header)?.groupValues?.getOrNull(1)
    val quoted = Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        .find(header)?.groupValues?.getOrNull(1)
    val unquoted = Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
        .find(header)?.groupValues?.getOrNull(1)
    val raw = rfc5987?.let(::percentDecodeUtf8) ?: quoted ?: unquoted?.trim()?.trim('"')
    val name = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()?.trim('"')
    return name?.takeIf { it.isNotEmpty() && it.contains('.') }
}

internal fun parseHttpSizeBytes(headers: Map<String, String>): Long? {
    val range = headers.entries.firstOrNull { it.key.equals("content-range", ignoreCase = true) }?.value
    val rangeTotal = Regex("""bytes\s+\d+-\d+/(\d+)""", RegexOption.IGNORE_CASE)
        .find(range.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
    if (rangeTotal != null && rangeTotal > 0L) return rangeTotal
    return headers.entries.firstOrNull { it.key.equals("content-length", ignoreCase = true) }
        ?.value
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
}

internal fun percentDecodeUtf8(value: String): String {
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (decoded != null) {
                bytes.add(decoded.toByte())
                index += 3
                continue
            }
        }
        bytes.add(char.code.toByte())
        index++
    }
    return bytes.toByteArray().decodeToString()
}

internal fun pickEvenIndices(count: Int, max: Int): List<Int> {
    if (count <= 0 || max <= 0) return emptyList()
    if (count <= max) return (0 until count).toList()
    if (max == 1) return listOf(0)
    return (0 until max).map { index -> index * (count - 1) / (max - 1) }.distinct()
}
