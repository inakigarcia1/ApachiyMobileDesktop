package com.nuvio.app.features.player.embedded

internal object MkvTextSubtitleParser {
    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_FLAG_FORCED = 0x55AAL
    private const val ID_NAME = 0x536EL
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_CODEC_PRIVATE = 0x63A2L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TIMESTAMP = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_DURATION = 0x9BL
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L
    private const val TRACK_TYPE_SUBTITLE = 0x11L

    fun parse(data: ByteArray): List<EmbeddedTextTrack> {
        if (data.size < 8) return emptyList()
        var offset = 0
        val tracks = mutableMapOf<Long, MutableTrack>()
        var timestampScale = 1_000_000L

        while (offset < data.size) {
            val header = readElementHeader(data, offset) ?: break
            val (id, size, headerSize) = header
            val contentStart = offset + headerSize
            if (id == ID_EBML) {
                offset = skipOrEnd(contentStart, size, data.size)
                continue
            }
            if (id == ID_SEGMENT) {
                parseSegment(data, contentStart, size, tracks, timestampScale).also {
                    timestampScale = it
                }
                break
            }
            offset = skipOrEnd(contentStart, size, data.size)
        }

        val scale = timestampScale.coerceAtLeast(1L)
        return tracks.values.mapNotNull { track ->
            val codec = codecOf(track.codecId) ?: return@mapNotNull null
            if (track.cues.isEmpty()) return@mapNotNull null
            val cues = track.cues.map { cue ->
                EmbeddedSubtitleCue(
                    startMs = cue.startNs / 1_000_000,
                    endMs = (cue.endNs / 1_000_000).coerceAtLeast(cue.startNs / 1_000_000 + 500),
                    text = cue.text,
                )
            }
            EmbeddedTextTrack(
                language = track.language,
                name = track.name,
                forced = track.forced,
                codec = codec,
                cues = cues,
                assHeader = track.codecPrivate?.decodeToString(),
            )
        }.also { _ -> scale }
    }

    private fun parseSegment(
        data: ByteArray,
        start: Int,
        size: Long,
        tracks: MutableMap<Long, MutableTrack>,
        initialScale: Long,
    ): Long {
        var offset = start
        val end = elementEnd(start, size, data.size)
        var timestampScale = initialScale
        while (offset < end) {
            val header = readElementHeader(data, offset) ?: break
            val (id, elemSize, headerSize) = header
            val contentStart = offset + headerSize
            when (id) {
                ID_INFO -> timestampScale = parseInfo(data, contentStart, elemSize, timestampScale)
                ID_TRACKS -> parseTracks(data, contentStart, elemSize, tracks)
                ID_CLUSTER -> parseCluster(data, contentStart, elemSize, tracks, timestampScale)
            }
            offset = skipOrEnd(contentStart, elemSize, end)
        }
        return timestampScale
    }

    private fun parseInfo(data: ByteArray, start: Int, size: Long, fallback: Long): Long {
        var offset = start
        val end = elementEnd(start, size, data.size)
        var scale = fallback
        while (offset < end) {
            val header = readElementHeader(data, offset) ?: break
            val (id, elemSize, headerSize) = header
            val contentStart = offset + headerSize
            if (id == ID_TIMESTAMP_SCALE) {
                scale = readUnsigned(data, contentStart, elemSize.toInt()).takeIf { it > 0 } ?: scale
            }
            offset = skipOrEnd(contentStart, elemSize, end)
        }
        return scale
    }

    private fun parseTracks(
        data: ByteArray,
        start: Int,
        size: Long,
        tracks: MutableMap<Long, MutableTrack>,
    ) {
        var offset = start
        val end = elementEnd(start, size, data.size)
        while (offset < end) {
            val header = readElementHeader(data, offset) ?: break
            val (id, elemSize, headerSize) = header
            val contentStart = offset + headerSize
            if (id == ID_TRACK_ENTRY) {
                parseTrackEntry(data, contentStart, elemSize)?.let { tracks[it.number] = it }
            }
            offset = skipOrEnd(contentStart, elemSize, end)
        }
    }

    private fun parseTrackEntry(data: ByteArray, start: Int, size: Long): MutableTrack? {
        var offset = start
        val end = elementEnd(start, size, data.size)
        var number = -1L
        var type = -1L
        var codecId = ""
        var language: String? = null
        var name: String? = null
        var forced = false
        var codecPrivate: ByteArray? = null
        while (offset < end) {
            val header = readElementHeader(data, offset) ?: break
            val (id, elemSize, headerSize) = header
            val contentStart = offset + headerSize
            val contentEnd = skipOrEnd(contentStart, elemSize, end)
            when (id) {
                ID_TRACK_NUMBER -> number = readUnsigned(data, contentStart, elemSize.toInt())
                ID_TRACK_TYPE -> type = readUnsigned(data, contentStart, elemSize.toInt())
                ID_CODEC_ID -> codecId = data.decodeString(contentStart, contentEnd)
                ID_LANGUAGE -> language = data.decodeString(contentStart, contentEnd)
                ID_NAME -> name = data.decodeString(contentStart, contentEnd)
                ID_FLAG_FORCED -> forced = readUnsigned(data, contentStart, elemSize.toInt()) != 0L
                ID_CODEC_PRIVATE -> codecPrivate = data.copyOfRange(contentStart, contentEnd)
            }
            offset = contentEnd
        }
        if (number < 0 || type != TRACK_TYPE_SUBTITLE || codecOf(codecId) == null) return null
        return MutableTrack(number, language, name, forced, codecId, codecPrivate)
    }

    private fun parseCluster(
        data: ByteArray,
        start: Int,
        size: Long,
        tracks: MutableMap<Long, MutableTrack>,
        timestampScale: Long,
    ) {
        var offset = start
        val end = elementEnd(start, size, data.size)
        var clusterTimestamp = 0L
        while (offset < end) {
            val header = readElementHeader(data, offset) ?: break
            val (id, elemSize, headerSize) = header
            val contentStart = offset + headerSize
            val contentEnd = skipOrEnd(contentStart, elemSize, end)
            when (id) {
                ID_TIMESTAMP -> clusterTimestamp = readUnsigned(data, contentStart, elemSize.toInt())
                ID_SIMPLE_BLOCK -> parseSimpleBlock(data, contentStart, contentEnd, clusterTimestamp, timestampScale, tracks)
                ID_BLOCK_GROUP -> parseBlockGroup(data, contentStart, contentEnd, clusterTimestamp, timestampScale, tracks)
            }
            offset = contentEnd
        }
    }

    private fun parseSimpleBlock(
        data: ByteArray,
        start: Int,
        end: Int,
        clusterTimestamp: Long,
        timestampScale: Long,
        tracks: MutableMap<Long, MutableTrack>,
    ) {
        val trackVint = readVint(data, start) ?: return
        val trackNumber = trackVint.first
        val track = tracks[trackNumber] ?: return
        val tsOffset = start + trackVint.second
        if (tsOffset + 3 > end) return
        val relative = ((data[tsOffset].toInt() shl 8) or (data[tsOffset + 1].toInt() and 0xFF)).toShort().toInt()
        val flags = data[tsOffset + 2].toInt() and 0xFF
        if (flags and 0x06 != 0) return
        val payloadStart = tsOffset + 3
        if (payloadStart >= end) return
        val text = data.decodeString(payloadStart, end).trim()
        if (text.isEmpty()) return
        val startNs = (clusterTimestamp + relative) * timestampScale
        track.cues += TimedCue(startNs, startNs + 2_000_000_000L, text)
    }

    private fun parseBlockGroup(
        data: ByteArray,
        start: Int,
        end: Int,
        clusterTimestamp: Long,
        timestampScale: Long,
        tracks: MutableMap<Long, MutableTrack>,
    ) {
        var offset = start
        var blockStart = -1
        var blockEnd = -1
        var duration: Long? = null
        while (offset < end) {
            val header = readElementHeader(data, offset) ?: break
            val (id, elemSize, headerSize) = header
            val contentStart = offset + headerSize
            val contentEnd = skipOrEnd(contentStart, elemSize, end)
            when (id) {
                ID_BLOCK -> {
                    blockStart = contentStart
                    blockEnd = contentEnd
                }
                ID_BLOCK_DURATION -> duration = readUnsigned(data, contentStart, elemSize.toInt())
            }
            offset = contentEnd
        }
        if (blockStart < 0) return
        val trackVint = readVint(data, blockStart) ?: return
        val track = tracks[trackVint.first] ?: return
        val tsOffset = blockStart + trackVint.second
        if (tsOffset + 3 > blockEnd) return
        val relative = ((data[tsOffset].toInt() shl 8) or (data[tsOffset + 1].toInt() and 0xFF)).toShort().toInt()
        val payloadStart = tsOffset + 3
        if (payloadStart >= blockEnd) return
        val text = data.decodeString(payloadStart, blockEnd).trim()
        if (text.isEmpty()) return
        val startNs = (clusterTimestamp + relative) * timestampScale
        val endNs = startNs + (duration ?: 2_000L) * timestampScale
        track.cues += TimedCue(startNs, endNs, text)
    }

    private fun codecOf(codecId: String): EmbeddedTextCodec? = when (codecId.uppercase()) {
        "S_TEXT/UTF8", "S_TEXT/ASCII", "S_UTF8" -> EmbeddedTextCodec.SubRip
        "S_TEXT/ASS" -> EmbeddedTextCodec.Ass
        "S_TEXT/SSA" -> EmbeddedTextCodec.Ssa
        "S_TEXT/WEBVTT" -> EmbeddedTextCodec.WebVtt
        else -> null
    }

    private data class MutableTrack(
        val number: Long,
        val language: String?,
        val name: String?,
        val forced: Boolean,
        val codecId: String,
        val codecPrivate: ByteArray?,
        val cues: MutableList<TimedCue> = mutableListOf(),
    )

    private data class TimedCue(val startNs: Long, val endNs: Long, val text: String)
}

internal object Mp4TextSubtitleParser {
    fun parse(data: ByteArray): List<EmbeddedTextTrack> {
        if (data.size < 8 || !hasFtyp(data)) return emptyList()
        val moov = findBox(data, 0, data.size, "moov") ?: return emptyList()
        val tracks = mutableListOf<EmbeddedTextTrack>()
        visitBoxes(data, moov.start, moov.end) { type, start, end ->
            if (type == "trak") {
                parseTrak(data, start, end)?.let(tracks::add)
            }
        }
        return tracks
    }

    private fun hasFtyp(data: ByteArray): Boolean {
        if (data.size < 8) return false
        return boxType(data, 0) == "ftyp" || boxType(data, 0) == "moov"
    }

    private fun parseTrak(data: ByteArray, start: Int, end: Int): EmbeddedTextTrack? {
        val mdia = findBox(data, start, end, "mdia") ?: return null
        val hdlr = findBox(data, mdia.start, mdia.end, "hdlr") ?: return null
        if (hdlr.end - hdlr.start < 16) return null
        val handler = data.decodeAscii(hdlr.start + 8, hdlr.start + 12)
        if (handler != "sbtl" && handler != "text" && handler != "subt") return null
        val mdhd = findBox(data, mdia.start, mdia.end, "mdhd")
        val timescale = mdhd?.let { readMdhdTimescale(data, it.start, it.end) } ?: 1000
        val minf = findBox(data, mdia.start, mdia.end, "minf") ?: return null
        val stbl = findBox(data, minf.start, minf.end, "stbl") ?: return null
        val stsd = findBox(data, stbl.start, stbl.end, "stsd") ?: return null
        val codec = detectTx3g(data, stsd.start, stsd.end) ?: return null
        val language = elngOrMdhdLanguage(data, mdia.start, mdia.end, mdhd)
        val samples = readTextSamples(data, stbl.start, stbl.end, timescale)
        if (samples.isEmpty()) return null
        return EmbeddedTextTrack(
            language = language,
            name = null,
            forced = false,
            codec = codec,
            cues = samples,
        )
    }

    private fun detectTx3g(data: ByteArray, start: Int, end: Int): EmbeddedTextCodec? {
        val payload = data.decodeAscii(start, end.coerceAtMost(start + 128)).lowercase()
        return when {
            payload.contains("tx3g") || payload.contains("text") -> EmbeddedTextCodec.SubRip
            payload.contains("wvtt") -> EmbeddedTextCodec.WebVtt
            else -> null
        }
    }

    private fun readMdhdTimescale(data: ByteArray, start: Int, end: Int): Int {
        if (end - start < 20) return 1000
        val version = data[start].toInt() and 0xFF
        return if (version == 1) {
            if (end - start < 28) 1000 else readInt(data, start + 20)
        } else {
            readInt(data, start + 12)
        }.coerceAtLeast(1)
    }

    private fun elngOrMdhdLanguage(data: ByteArray, mdiaStart: Int, mdiaEnd: Int, mdhd: BoxRange?): String? {
        val elng = findBox(data, mdiaStart, mdiaEnd, "elng")
        if (elng != null && elng.end > elng.start + 4) {
            return data.decodeString(elng.start + 4, elng.end).trim().trimEnd('\u0000').ifBlank { null }
        }
        return null
    }

    private fun readTextSamples(
        data: ByteArray,
        stblStart: Int,
        stblEnd: Int,
        timescale: Int,
    ): List<EmbeddedSubtitleCue> {
        val stsz = findBox(data, stblStart, stblEnd, "stsz") ?: return emptyList()
        val stco = findBox(data, stblStart, stblEnd, "stco") ?: findBox(data, stblStart, stblEnd, "co64")
        val stts = findBox(data, stblStart, stblEnd, "stts") ?: return emptyList()
        if (stco == null) return emptyList()
        val sizes = readStsz(data, stsz.start, stsz.end)
        val offsets = if (boxType(data, stco.start - 8) == "co64" || (stco.start >= 8 && boxType(data, stco.start - 8) == "co64")) {
            readCo64(data, stco.start, stco.end)
        } else {
            readStco(data, stco.start, stco.end)
        }
        val durations = readSttsSampleDurations(data, stts.start, stts.end, sizes.size)
        val cues = mutableListOf<EmbeddedSubtitleCue>()
        var dts = 0L
        val count = minOf(sizes.size, offsets.size, durations.size)
        for (i in 0 until count) {
            val size = sizes[i]
            val offset = offsets[i].toInt()
            if (size <= 0 || offset < 0 || offset + size > data.size) {
                dts += durations[i]
                continue
            }
            val text = decodeTx3gSample(data, offset, size)
            val startMs = dts * 1000 / timescale
            val endMs = startMs + (durations[i] * 1000 / timescale).coerceAtLeast(500)
            if (text.isNotBlank()) {
                cues += EmbeddedSubtitleCue(startMs, endMs, text)
            }
            dts += durations[i]
        }
        return cues
    }

    private fun decodeTx3gSample(data: ByteArray, offset: Int, size: Int): String {
        if (size < 2) return ""
        val length = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        val textStart = offset + 2
        val textEnd = (textStart + length).coerceAtMost(offset + size)
        if (textEnd <= textStart) return ""
        return data.decodeString(textStart, textEnd).trim()
    }

    private fun readStsz(data: ByteArray, start: Int, end: Int): IntArray {
        if (end - start < 12) return intArrayOf()
        val sampleSize = readInt(data, start + 4)
        val count = readInt(data, start + 8).coerceAtLeast(0)
        if (sampleSize != 0) return IntArray(count) { sampleSize }
        if (end - start < 12 + count * 4) return intArrayOf()
        return IntArray(count) { i -> readInt(data, start + 12 + i * 4) }
    }

    private fun readStco(data: ByteArray, start: Int, end: Int): LongArray {
        if (end - start < 8) return longArrayOf()
        val count = readInt(data, start + 4).coerceAtLeast(0)
        if (end - start < 8 + count * 4) return longArrayOf()
        return LongArray(count) { i -> readInt(data, start + 8 + i * 4).toLong() and 0xFFFFFFFFL }
    }

    private fun readCo64(data: ByteArray, start: Int, end: Int): LongArray {
        if (end - start < 8) return longArrayOf()
        val count = readInt(data, start + 4).coerceAtLeast(0)
        if (end - start < 8 + count * 8) return longArrayOf()
        return LongArray(count) { i -> readLong(data, start + 8 + i * 8) }
    }

    private fun readSttsSampleDurations(data: ByteArray, start: Int, end: Int, sampleCount: Int): LongArray {
        if (end - start < 8) return LongArray(sampleCount) { 1000 }
        val entryCount = readInt(data, start + 4).coerceAtLeast(0)
        val durations = LongArray(sampleCount)
        var filled = 0
        var cursor = start + 8
        repeat(entryCount) {
            if (cursor + 8 > end || filled >= sampleCount) return@repeat
            val count = readInt(data, cursor).coerceAtLeast(0)
            val duration = readInt(data, cursor + 4).toLong().coerceAtLeast(1L)
            cursor += 8
            repeat(count) {
                if (filled < sampleCount) {
                    durations[filled] = duration
                    filled++
                }
            }
        }
        while (filled < sampleCount) {
            durations[filled] = 1000
            filled++
        }
        return durations
    }

    private data class BoxRange(val start: Int, val end: Int)

    private fun findBox(data: ByteArray, start: Int, end: Int, wanted: String): BoxRange? {
        var offset = start
        while (offset + 8 <= end) {
            val size = readInt(data, offset)
            val type = boxType(data, offset)
            val boxEnd = when {
                size == 1 && offset + 16 <= end -> {
                    val large = readLong(data, offset + 8)
                    offset + large.toInt().coerceAtLeast(16)
                }
                size >= 8 -> offset + size
                else -> return null
            }.coerceAtMost(end)
            val header = if (size == 1) 16 else 8
            if (type == wanted) return BoxRange(offset + header, boxEnd)
            offset = boxEnd
        }
        return null
    }

    private fun visitBoxes(data: ByteArray, start: Int, end: Int, visitor: (String, Int, Int) -> Unit) {
        var offset = start
        while (offset + 8 <= end) {
            val size = readInt(data, offset)
            val type = boxType(data, offset)
            val boxEnd = if (size >= 8) (offset + size).coerceAtMost(end) else return
            val header = 8
            visitor(type, offset + header, boxEnd)
            offset = boxEnd
        }
    }

    private fun boxType(data: ByteArray, offset: Int): String = data.decodeAscii(offset + 4, offset + 8)

    private fun readInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun readLong(data: ByteArray, offset: Int): Long {
        if (offset + 8 > data.size) return 0L
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (data[offset + i].toInt() and 0xFF).toLong()
        }
        return value
    }
}

private data class ElementHeader(val id: Long, val size: Long, val headerSize: Int)

private fun readElementHeader(data: ByteArray, offset: Int): ElementHeader? {
    val id = readId(data, offset) ?: return null
    val sizeVint = readVint(data, offset + id.second) ?: return null
    return ElementHeader(id.first, sizeVint.first, id.second + sizeVint.second)
}

private fun readId(data: ByteArray, offset: Int): Pair<Long, Int>? {
    if (offset >= data.size) return null
    val first = data[offset].toInt() and 0xFF
    var length = 1
    var mask = 0x80
    while (length <= 4 && first and mask == 0) {
        length++
        mask = mask shr 1
    }
    if (offset + length > data.size) return null
    var value = 0L
    for (i in 0 until length) {
        value = (value shl 8) or (data[offset + i].toInt() and 0xFF).toLong()
    }
    return value to length
}

private fun readVint(data: ByteArray, offset: Int): Pair<Long, Int>? {
    if (offset >= data.size) return null
    val first = data[offset].toInt() and 0xFF
    var length = 1
    var mask = 0x80
    while (length <= 8 && first and mask == 0) {
        length++
        mask = mask shr 1
    }
    if (offset + length > data.size) return null
    var value = (first and (mask - 1)).toLong()
    val unknown = (1L shl (7 * length)) - 1
    for (i in 1 until length) {
        value = (value shl 8) or (data[offset + i].toInt() and 0xFF).toLong()
    }
    if (value == unknown) {
        value = (data.size - (offset + length)).toLong().coerceAtLeast(0L)
    }
    return value to length
}

private fun readUnsigned(data: ByteArray, offset: Int, length: Int): Long {
    val end = (offset + length).coerceAtMost(data.size)
    var value = 0L
    for (i in offset until end) {
        value = (value shl 8) or (data[i].toInt() and 0xFF).toLong()
    }
    return value
}

private fun elementEnd(start: Int, size: Long, limit: Int): Int {
    val computed = start + size.toInt().coerceAtLeast(0)
    return computed.coerceAtMost(limit).coerceAtLeast(start)
}

private fun skipOrEnd(contentStart: Int, size: Long, limit: Int): Int = elementEnd(contentStart, size, limit)

private fun ByteArray.decodeString(start: Int, end: Int): String {
    val from = start.coerceIn(0, size)
    val to = end.coerceIn(from, size)
    if (to <= from) return ""
    return decodeToString(from, to).trimEnd('\u0000')
}

private fun ByteArray.decodeAscii(start: Int, end: Int): String {
    val from = start.coerceIn(0, size)
    val to = end.coerceIn(from, size)
    if (to <= from) return ""
    return copyOfRange(from, to).decodeToString()
}
