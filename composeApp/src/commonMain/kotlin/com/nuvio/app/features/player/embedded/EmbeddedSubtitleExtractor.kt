package com.nuvio.app.features.player.embedded

import com.nuvio.app.features.addons.httpGetBytesWithHeaders
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.addons.readLocalFilePrefix
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal object EmbeddedSubtitleExtractor {
    private const val PREFIX_BYTES = 2 * 1024 * 1024
    private const val CUES_BYTES = 2 * 1024 * 1024
    private const val CLUSTER_WINDOW_BYTES = 768 * 1024
    private const val BLOCK_BYTES = 16 * 1024
    private const val MAX_CLUSTER_FETCHES = 24
    private const val MAX_BLOCK_FETCHES = 96
    private const val TARGET_REFERENCE_CUES = 80

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
            httpRequestRaw("GET", trimmed, headers + mapOf("Range" to "bytes=0-0"), "", true, 8)
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
        if (isMkv && layout != null && !hasDenseReference(tracks)) {
            tracks = harvestFromCues(sourceUrl, headers, videoSize, prefix, layout, tracks)
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

    private suspend fun harvestFromCues(
        sourceUrl: String,
        headers: Map<String, String>,
        videoSize: Long?,
        prefix: ByteArray,
        layout: MkvLayout,
        seedTracks: List<EmbeddedTextTrack>,
    ): List<EmbeddedTextTrack> {
        val cuesOffset = layout.cuesOffset ?: return seedTracks
        val cuesBytes = if (cuesOffset < prefix.size - 8) {
            prefix.copyOfRange(cuesOffset.toInt().coerceAtLeast(0), prefix.size)
        } else {
            readMediaRange(sourceUrl, headers, cuesOffset, CUES_BYTES)
        }
        if (cuesBytes == null) {
            return seedTracks
        }
        val cueRefs = MkvTextSubtitleParser.parseCues(cuesBytes)
        val srtTrackNumbers = seedTracks
            .filter { it.codec == EmbeddedTextCodec.SubRip && it.trackNumber > 0L }
            .map { it.trackNumber }
            .toSet()
        val trackNumbers = srtTrackNumbers.ifEmpty {
            seedTracks.map { it.trackNumber }.filter { it > 0 }.toSet()
        }
        val subtitleRefs = cueRefs
            .filter { ref -> trackNumbers.isEmpty() || ref.trackNumber in trackNumbers }
            .ifEmpty { cueRefs }
        val relativeRefs = subtitleRefs
            .filter { it.relativePosition != null }
            .sortedBy { it.timeTicks }
        var tracks = seedTracks
        val clusters = subtitleRefs
            .sortedBy { it.timeTicks }
            .distinctBy { it.clusterPosition }
        val chosenClusters = pickEvenIndices(clusters.size, MAX_CLUSTER_FETCHES).map { index -> clusters[index] }
        coroutineScope {
            for (chunk in chosenClusters.chunked(4)) {
                val windows = chunk.map { cluster ->
                    async {
                        val fileOffset = layout.segmentDataOffset + cluster.clusterPosition
                        if (videoSize != null && fileOffset >= videoSize) return@async null
                        readMediaRange(sourceUrl, headers, fileOffset, CLUSTER_WINDOW_BYTES)
                    }
                }.awaitAll()
                for (window in windows) {
                    if (window == null) continue
                    tracks = MkvTextSubtitleParser.harvestClusterWindow(window, tracks, layout.timestampScale)
                }
            }
        }
        if (!hasDenseReference(tracks) && relativeRefs.isNotEmpty()) {
            val chosen = pickEvenIndices(relativeRefs.size, MAX_BLOCK_FETCHES).map { index -> relativeRefs[index] }
            coroutineScope {
                for (chunk in chosen.chunked(8)) {
                    val windows = chunk.map { ref ->
                        async {
                            val clusterFile = layout.segmentDataOffset + ref.clusterPosition
                            val relative = ref.relativePosition ?: return@async null
                            val blockFile = clusterFile + relative
                            if (videoSize != null && blockFile >= videoSize) return@async null
                            val start = (blockFile - 4L).coerceAtLeast(clusterFile)
                            val window = readMediaRange(sourceUrl, headers, start, BLOCK_BYTES) ?: return@async null
                            ref to window
                        }
                    }.awaitAll()
                    for (pair in windows) {
                        if (pair == null) continue
                        tracks = MkvTextSubtitleParser.harvestBlockWindow(
                            pair.second,
                            tracks,
                            layout.timestampScale,
                            pair.first.timeTicks,
                        )
                    }
                }
            }
        }
        return tracks
    }

    private fun hasDenseReference(tracks: List<EmbeddedTextTrack>): Boolean {
        val chosen = selectEmbeddedReferenceTrack(tracks) ?: return false
        return chosen.cues.size >= TARGET_REFERENCE_CUES && isUsableEmbeddedReference(chosen)
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
        val rangeHeaders = headers + mapOf("Range" to "bytes=$startByte-$endByte")
        return httpGetBytesWithHeaders(trimmed, rangeHeaders, maxBytes)
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
