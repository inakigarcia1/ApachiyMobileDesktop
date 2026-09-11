package com.nuvio.app.features.player.embedded

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.player.AddonSubtitle
import com.nuvio.app.features.player.SubtitleLanguageMatching
import com.nuvio.app.features.player.SubtitleTrack

private val FORCED_TAGS = listOf("forced", "signs", "songs", "sign", "song")
private val SDH_TAGS = listOf("sdh", "cc", "hearing impaired", "hi)", "(hi", "caption")

fun isEmbeddedEnglishLanguage(
    language: String?,
    label: String? = null,
    trackId: String? = null,
): Boolean {
    val fields = listOfNotNull(language, label, trackId)
    if (fields.isEmpty()) return false
    for (field in fields) {
        val code = field.trim().lowercase().replace('_', '-')
        if (code == "en" || code == "eng" || code.startsWith("en-") || code.startsWith("eng-")) {
            return true
        }
        val normalized = SubtitleLanguageMatching.normalizeLanguageCode(field)
        if (normalized == "en" || normalized.startsWith("en-")) {
            return true
        }
    }
    val haystack = fields.joinToString(" ").lowercase()
    return haystack.contains("english")
}

fun isForcedTextTrack(forcedFlag: Boolean, language: String?, name: String?): Boolean {
    if (forcedFlag) return true
    return SubtitleLanguageMatching.subtitleHasAnyTag(name, language, null, FORCED_TAGS)
}

fun isSdhTextTrack(language: String?, name: String?): Boolean =
    SubtitleLanguageMatching.subtitleHasAnyTag(name, language, null, SDH_TAGS)

fun hasEmbeddedSpanishSubtitleTrack(tracks: Collection<SubtitleTrack>): Boolean =
    SubtitleLanguageMatching.hasEmbeddedSpanishSubtitleTrack(tracks)

fun hasEmbeddedSpanishTextTrack(tracks: Collection<EmbeddedTextTrack>): Boolean =
    tracks.any { SubtitleLanguageMatching.isEmbeddedSpanishLanguage(it.language, it.name, null) }

fun selectEmbeddedReferenceTrack(tracks: List<EmbeddedTextTrack>): EmbeddedTextTrack? {
    val usable = tracks.filter { it.cues.isNotEmpty() }
        .filterNot { SubtitleLanguageMatching.isEmbeddedSpanishLanguage(it.language, it.name, null) }
    if (usable.isEmpty()) return null

    val english = usable.filter { isEmbeddedEnglishLanguage(it.language, it.name) }
    val pool = english.ifEmpty { usable }
    val nonForced = pool.filterNot { isForcedTextTrack(it.forced, it.language, it.name) }
    val dialoguePool = nonForced.ifEmpty { pool }
    val nonSdh = dialoguePool.filterNot { isSdhTextTrack(it.language, it.name) }
    val ranked = (nonSdh.ifEmpty { dialoguePool }).sortedWith(
        compareByDescending<EmbeddedTextTrack> { it.cues.size }
            .thenBy { it.name.orEmpty() },
    )
    return ranked.firstOrNull()
}

fun EmbeddedTextTrack.toReference(): EmbeddedSubtitleReference {
    val (body, filename) = when (codec) {
        EmbeddedTextCodec.Ass, EmbeddedTextCodec.Ssa -> assBody() to "embedded.${if (codec == EmbeddedTextCodec.Ssa) "ssa" else "ass"}"
        EmbeddedTextCodec.WebVtt -> toWebVtt() to "embedded.vtt"
        EmbeddedTextCodec.SubRip -> toSrt() to "embedded.srt"
    }
    return EmbeddedSubtitleReference(
        bytes = body.encodeToByteArray(),
        filename = filename,
        language = language,
    )
}

fun selectPreferredSpanishAddonSubtitle(subtitles: List<AddonSubtitle>): AddonSubtitle? =
    subtitles.firstOrNull { SubtitleLanguageMatching.isEmbeddedSpanishLanguage(it.language, it.display, it.id) }

fun isApachiySubtitleAddon(manifest: AddonManifest, subtitleUrl: String = ""): Boolean {
    if (manifest.id.equals("com.apachiy.addon", ignoreCase = true)) return true
    val haystack = "${manifest.transportUrl} $subtitleUrl"
    return haystack.contains("/apachiy/subtitles", ignoreCase = true) ||
        haystack.contains("/apachiy/", ignoreCase = true)
}

private fun EmbeddedTextTrack.toSrt(): String = buildString {
    cues.forEachIndexed { index, cue ->
        append(index + 1)
        append("\n")
        append(formatSrtTimestamp(cue.startMs))
        append(" --> ")
        append(formatSrtTimestamp(cue.endMs.coerceAtLeast(cue.startMs + 500)))
        append("\n")
        append(cue.text.trim())
        append("\n\n")
    }
}

private fun EmbeddedTextTrack.toWebVtt(): String = buildString {
    append("WEBVTT\n\n")
    cues.forEach { cue ->
        append(formatVttTimestamp(cue.startMs))
        append(" --> ")
        append(formatVttTimestamp(cue.endMs.coerceAtLeast(cue.startMs + 500)))
        append("\n")
        append(cue.text.trim())
        append("\n\n")
    }
}

private fun EmbeddedTextTrack.assBody(): String = buildString {
    val header = assHeader?.trim().orEmpty()
    if (header.isNotEmpty()) {
        append(header)
        if (!header.endsWith("\n")) append('\n')
        if (!header.contains("[Events]", ignoreCase = true)) {
            append("\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        }
    } else {
        append("[Script Info]\nScriptType: v4.00+\n\n[Events]\n")
        append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
    }
    cues.forEach { cue ->
        append("Dialogue: 0,")
        append(formatAssTimestamp(cue.startMs))
        append(",")
        append(formatAssTimestamp(cue.endMs.coerceAtLeast(cue.startMs + 500)))
        append(",Default,,0,0,0,,")
        append(cue.text.trim().replace("\n", "\\N"))
        append('\n')
    }
}

internal fun formatSrtTimestamp(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val hours = clamped / 3_600_000
    val minutes = (clamped % 3_600_000) / 60_000
    val seconds = (clamped % 60_000) / 1_000
    val millis = clamped % 1_000
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')},${millis.toString().padStart(3, '0')}"
}

private fun formatVttTimestamp(ms: Long): String = formatSrtTimestamp(ms).replace(',', '.')

private fun formatAssTimestamp(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val hours = clamped / 3_600_000
    val minutes = (clamped % 3_600_000) / 60_000
    val seconds = (clamped % 60_000) / 1_000
    val centis = (clamped % 1_000) / 10
    return "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}.${centis.toString().padStart(2, '0')}"
}
