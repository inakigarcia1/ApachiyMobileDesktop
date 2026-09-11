package com.nuvio.app.features.player.embedded

import com.nuvio.app.features.addons.httpGetBytesWithHeaders
import com.nuvio.app.features.addons.readLocalFilePrefix

internal object EmbeddedSubtitleExtractor {
    private const val PREFIX_BYTES = 16 * 1024 * 1024

    suspend fun extract(
        sourceUrl: String,
        headers: Map<String, String>,
    ): EmbeddedExtractResult? {
        val prefix = readMediaPrefix(sourceUrl, headers, PREFIX_BYTES) ?: return null
        val tracks = when {
            looksLikeMkv(prefix) -> MkvTextSubtitleParser.parse(prefix)
            looksLikeMp4(prefix) -> Mp4TextSubtitleParser.parse(prefix)
            else -> emptyList()
        }
        if (tracks.isEmpty()) return null
        val hasSpanish = hasEmbeddedSpanishTextTrack(tracks)
        if (hasSpanish) {
            return EmbeddedExtractResult(hasEmbeddedSpanish = true, reference = null)
        }
        val chosen = selectEmbeddedReferenceTrack(tracks) ?: return EmbeddedExtractResult(false, null)
        return EmbeddedExtractResult(false, chosen.toReference())
    }

    private suspend fun readMediaPrefix(
        sourceUrl: String,
        headers: Map<String, String>,
        maxBytes: Int,
    ): ByteArray? {
        val trimmed = sourceUrl.trim()
        if (trimmed.startsWith("file:", ignoreCase = true)) {
            val path = trimmed.removePrefix("file://").removePrefix("file:")
            return readLocalFilePrefix(path, maxBytes)
        }
        if (trimmed.startsWith("/") && !trimmed.startsWith("//")) {
            return readLocalFilePrefix(trimmed, maxBytes)
        }
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return readLocalFilePrefix(trimmed, maxBytes)
        }
        val rangeHeaders = headers + mapOf("Range" to "bytes=0-${maxBytes - 1}")
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
