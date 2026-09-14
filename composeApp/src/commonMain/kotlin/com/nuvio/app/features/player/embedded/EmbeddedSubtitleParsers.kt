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
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_CUES = 0x1C53BB6BL
    private const val ID_CUE_POINT = 0xBBL
    private const val ID_CUE_TIME = 0xB3L
    private const val ID_CUE_TRACK_POSITIONS = 0xB7L
    private const val ID_CUE_TRACK = 0xF7L
    private const val ID_CUE_CLUSTER_POSITION = 0xF1L
    private const val ID_CUE_RELATIVE_POSITION = 0xF0L
    private const val TRACK_TYPE_SUBTITLE = 0x11L

    fun parse(data: ByteArray): List<EmbeddedTextTrack> = parseLayout(data).tracks

    fun parseLayout(data: ByteArray): MkvLayout {
        if (data.size < 8) {
            return MkvLayout(emptyList(), 1_000_000L, 0L, null)
        }
        var offset = 0
        val tracks = mutableMapOf<Long, MutableTrack>()
        var timestampScale = 1_000_000L
        var segmentDataOffset = 0L
        var cuesRelative: Long? = null

        while (offset < data.size) {
            val header = readElementHeader(data, offset) ?: break
            val (id, size, headerSize) = header
            val contentStart = offset + headerSize
            if (id == ID_EBML) {
                offset = skipOrEnd(contentStart, size, data.size)
                continue
            }
            if (id == ID_SEGMENT) {
                segmentDataOffset = contentStart.toLong()
                val meta = parseSegment(data, contentStart, size, tracks, timestampScale)
                timestampScale = meta.timestampScale
                cuesRelative = meta.cuesRelativeOffset
                break
            }
            offset = skipOrEnd(contentStart, size, data.size)
        }

        val cuesOffset = cuesRelative?.let { relative -> segmentDataOffset + relative }
        return MkvLayout(
            tracks = tracks.values.mapNotNull { track -> toTextTrack(track) },
            timestampScale = timestampScale.coerceAtLeast(1L),
            segmentDataOffset = segmentDataOffset,
            cuesOffset = cuesOffset,
        )
    }

    fun parseCues(data: ByteArray): List<MkvCueRef> {
        if (data.size < 4) return emptyList()
        var offset = 0
        val header = readElementHeader(data, 0)
        val payloadStart: Int
        val payloadEnd: Int
        if (header?.id == ID_CUES) {
            payloadStart = header.headerSize
            payloadEnd = elementEnd(header.headerSize, header.size, data.size)
        } else {
            payloadStart = 0
            payloadEnd = data.size
        }
        val refs = mutableListOf<MkvCueRef>()
        offset = payloadStart
        while (offset < payloadEnd) {
            val pointHeader = readElementHeader(data, offset) ?: break
            val contentStart = offset + pointHeader.headerSize
            val contentEnd = skipOrEnd(contentStart, pointHeader.size, payloadEnd)
            if (pointHeader.id == ID_CUE_POINT) {
                refs += parseCuePoint(data, contentStart, contentEnd)
            }
            offset = contentEnd
        }
        return refs
    }

    fun harvestClusterWindow(
        window: ByteArray,
        existing: List<EmbeddedTextTrack>,
        timestampScale: Long,
    ): List<EmbeddedTextTrack> {
        if (window.size < 8 || existing.isEmpty()) return existing
        val mutable = existing.associate { track ->
            track.trackNumber to MutableTrack(
                number = track.trackNumber,
                language = track.language,
                name = track.name,
                forced = track.forced,
                codecId = codecIdOf(track.codec),
                codecPrivate = track.assHeader?.encodeToByteArray(),
                cues = track.cues.map { cue ->
                    TimedCue(cue.startMs * 1_000_000L, cue.endMs * 1_000_000L, cue.text)
                }.toMutableList(),
            )
        }.toMutableMap()
        var offset = 0
        val scanLimit = minOf(window.size - 4, 128)
        while (offset < scanLimit && !startsWithCluster(window, offset)) {
            offset++
        }
        val header = readElementHeader(window, offset) ?: return existing
        if (header.id != ID_CLUSTER) return existing
        parseCluster(window, offset + header.headerSize, header.size, mutable, timestampScale.coerceAtLeast(1L))
        return mutable.values.mapNotNull { track -> toTextTrack(track) }
    }

    fun harvestBlockWindow(
        window: ByteArray,
        existing: List<EmbeddedTextTrack>,
        timestampScale: Long,
        cueTimeTicks: Long,
    ): List<EmbeddedTextTrack> {
        if (window.size < 4 || existing.isEmpty()) return existing
        val mutable = existingToMutable(existing)
        val scale = timestampScale.coerceAtLeast(1L)
        // CueRelativePosition is from the Cluster payload, so the SimpleBlock sits a
        // few bytes into this window (Cluster ID+size). Scan a tight prefix for the
        // first subtitle block; do not walk the whole 16KB or later cues get this time.
        val scanLimit = minOf(window.size - 4, 96)
        var at = 0
        while (at < scanLimit) {
            val idByte = window[at].toInt() and 0xFF
            if ((idByte == 0xA3 || idByte == 0xA0) &&
                tryParseBlockAt(window, at, mutable, scale, cueTimeTicks)
            ) {
                break
            }
            at++
        }
        return mutable.values.mapNotNull { track -> toTextTrack(track) }
    }

    private fun existingToMutable(existing: List<EmbeddedTextTrack>): MutableMap<Long, MutableTrack> =
        existing.associate { track ->
            track.trackNumber to MutableTrack(
                number = track.trackNumber,
                language = track.language,
                name = track.name,
                forced = track.forced,
                codecId = codecIdOf(track.codec),
                codecPrivate = track.assHeader?.encodeToByteArray(),
                cues = track.cues.map { cue ->
                    TimedCue(cue.startMs * 1_000_000L, cue.endMs * 1_000_000L, cue.text)
                }.toMutableList(),
            )
        }.toMutableMap()

    private fun tryParseBlockAt(
        window: ByteArray,
        offset: Int,
        tracks: MutableMap<Long, MutableTrack>,
        timestampScale: Long,
        cueTimeTicks: Long,
    ): Boolean {
        val header = readElementHeader(window, offset) ?: return false
        val before = tracks.values.sumOf { it.cues.size }
        val contentStart = offset + header.headerSize
        val contentEnd = skipOrEnd(contentStart, header.size, window.size)
        when (header.id) {
            ID_SIMPLE_BLOCK -> parseSimpleBlock(
                window, contentStart, contentEnd, 0L, timestampScale, tracks, cueTimeTicks,
            )
            ID_BLOCK_GROUP -> parseBlockGroup(
                window, contentStart, contentEnd, 0L, timestampScale, tracks, cueTimeTicks,
            )
            else -> return false
        }
        return tracks.values.sumOf { it.cues.size } > before
    }

    private fun parseCuePoint(data: ByteArray, start: Int, end: Int): List<MkvCueRef> {
        var offset = start
        var timeTicks = 0L
        val refs = mutableListOf<MkvCueRef>()
        while (offset < end) {
            val header = readElementHeader(data, offset) ?: break
            val contentStart = offset + header.headerSize
            val contentEnd = skipOrEnd(contentStart, header.size, end)
            when (header.id) {
                ID_CUE_TIME -> timeTicks = readUnsigned(data, contentStart, header.size.toInt())
                ID_CUE_TRACK_POSITIONS -> {
                    var trackNumber = -1L
                    var clusterPosition = -1L
                    var relativePosition: Long? = null
                    var inner = contentStart
                    while (inner < contentEnd) {
                        val innerHeader = readElementHeader(data, inner) ?: break
                        val innerStart = inner + innerHeader.headerSize
                        val innerEnd = skipOrEnd(innerStart, innerHeader.size, contentEnd)
                        when (innerHeader.id) {
                            ID_CUE_TRACK -> trackNumber = readUnsigned(data, innerStart, innerHeader.size.toInt())
                            ID_CUE_CLUSTER_POSITION -> clusterPosition = readUnsigned(data, innerStart, innerHeader.size.toInt())
                            ID_CUE_RELATIVE_POSITION -> relativePosition = readUnsigned(data, innerStart, innerHeader.size.toInt())
                        }
                        inner = innerEnd
                    }
                    if (trackNumber >= 0 && clusterPosition >= 0) {
                        refs += MkvCueRef(timeTicks, trackNumber, clusterPosition, relativePosition)
                    }
                }
            }
            offset = contentEnd
        }
        return refs
    }

    private fun toTextTrack(track: MutableTrack): EmbeddedTextTrack? {
        val codec = codecOf(track.codecId) ?: return null
        val cues = track.cues
            .map { cue ->
                EmbeddedSubtitleCue(
                    startMs = cue.startNs / 1_000_000,
                    endMs = (cue.endNs / 1_000_000).coerceAtLeast(cue.startNs / 1_000_000 + 500),
                    text = cue.text,
                )
            }
            .sortedBy { it.startMs }
        return EmbeddedTextTrack(
            language = track.language,
            name = track.name,
            forced = track.forced,
            codec = codec,
            cues = cues,
            assHeader = track.codecPrivate?.decodeToString(),
            trackNumber = track.number,
        )
    }

    private fun codecIdOf(codec: EmbeddedTextCodec): String = when (codec) {
        EmbeddedTextCodec.SubRip -> "S_TEXT/UTF8"
        EmbeddedTextCodec.Ass -> "S_TEXT/ASS"
        EmbeddedTextCodec.Ssa -> "S_TEXT/SSA"
        EmbeddedTextCodec.WebVtt -> "S_TEXT/WEBVTT"
    }

    private fun startsWithCluster(data: ByteArray, offset: Int): Boolean =
        offset + 4 <= data.size &&
            (data[offset].toInt() and 0xFF) == 0x1F &&
            (data[offset + 1].toInt() and 0xFF) == 0x43 &&
            (data[offset + 2].toInt() and 0xFF) == 0xB6 &&
            (data[offset + 3].toInt() and 0xFF) == 0x75

    private class SegmentMeta(
        var timestampScale: Long,
        var cuesRelativeOffset: Long? = null,
    )

    private fun parseSegment(
        data: ByteArray,
        start: Int,
        size: Long,
        tracks: MutableMap<Long, MutableTrack>,
        initialScale: Long,
    ): SegmentMeta {
        var offset = start
        val end = elementEnd(start, size, data.size)
        val meta = SegmentMeta(initialScale)
        while (offset < end) {
            val header = readElementHeader(data, offset) ?: break
            val (id, elemSize, headerSize) = header
            val contentStart = offset + headerSize
            when (id) {
                ID_SEEK_HEAD -> {
                    val cuesRel = parseSeekHead(data, contentStart, elemSize)
                    if (cuesRel != null) meta.cuesRelativeOffset = cuesRel
                }
                ID_INFO -> meta.timestampScale = parseInfo(data, contentStart, elemSize, meta.timestampScale)
                ID_TRACKS -> parseTracks(data, contentStart, elemSize, tracks)
                ID_CLUSTER -> parseCluster(data, contentStart, elemSize, tracks, meta.timestampScale)
            }
            offset = skipOrEnd(contentStart, elemSize, end)
        }
        return meta
    }

    private fun parseSeekHead(data: ByteArray, start: Int, size: Long): Long? {
        var offset = start
        val end = elementEnd(start, size, data.size)
        var cuesRelative: Long? = null
        while (offset < end) {
            val header = readElementHeader(data, offset) ?: break
            val contentStart = offset + header.headerSize
            val contentEnd = skipOrEnd(contentStart, header.size, end)
            if (header.id == ID_SEEK) {
                var seekId = -1L
                var seekPosition = -1L
                var inner = contentStart
                while (inner < contentEnd) {
                    val innerHeader = readElementHeader(data, inner) ?: break
                    val innerStart = inner + innerHeader.headerSize
                    val innerEnd = skipOrEnd(innerStart, innerHeader.size, contentEnd)
                    when (innerHeader.id) {
                        ID_SEEK_ID -> seekId = readId(data, innerStart)?.first ?: -1L
                        ID_SEEK_POSITION -> seekPosition = readUnsigned(data, innerStart, innerHeader.size.toInt())
                    }
                    inner = innerEnd
                }
                if (seekId == ID_CUES && seekPosition >= 0) {
                    cuesRelative = seekPosition
                }
            }
            offset = contentEnd
        }
        return cuesRelative
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
        absoluteTicks: Long? = null,
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
        val startNs = if (absoluteTicks != null) {
            absoluteTicks * timestampScale
        } else {
            (clusterTimestamp + relative) * timestampScale
        }
        if (track.cues.any { it.startNs == startNs && it.text == text }) return
        track.cues += TimedCue(startNs, startNs + 2_000_000_000L, text)
    }

    private fun parseBlockGroup(
        data: ByteArray,
        start: Int,
        end: Int,
        clusterTimestamp: Long,
        timestampScale: Long,
        tracks: MutableMap<Long, MutableTrack>,
        absoluteTicks: Long? = null,
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
        val startNs = if (absoluteTicks != null) {
            absoluteTicks * timestampScale
        } else {
            (clusterTimestamp + relative) * timestampScale
        }
        val endNs = startNs + (duration ?: 2_000L) * timestampScale
        if (track.cues.any { it.startNs == startNs && it.text == text }) return
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

private fun elementEnd(start: Int, size: Long, limit: Int): Int = mkvBoundedEnd(start, size, limit)

/**
 * EBML element sizes are 64-bit. A Segment for a BDRemux is several GB, so
 * `size.toInt()` overflows and the parser never walks Tracks/Clusters inside
 * a truncated prefix.
 */
internal fun mkvBoundedEnd(start: Int, size: Long, limit: Int): Int {
    if (start >= limit) return limit
    val remaining = (limit.toLong() - start.toLong()).coerceAtLeast(0L)
    val take = size.coerceAtLeast(0L).coerceAtMost(remaining)
    return start + take.toInt()
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
