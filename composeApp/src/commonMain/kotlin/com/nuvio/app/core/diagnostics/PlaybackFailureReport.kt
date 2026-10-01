package com.nuvio.app.core.diagnostics

internal data class PlaybackFailureDetails(
    val message: String,
    val engine: String? = null,
    val errorCode: String? = null,
    val exceptionClass: String? = null,
    val causeClass: String? = null,
    val causeMessage: String? = null,
    val videoCodec: String? = null,
    val mimeType: String? = null,
)

internal data class PlaybackFailureReport(
    val message: String,
    val engine: String?,
    val host: String?,
    val provider: String?,
    val streamType: String?,
    val streamLabel: String?,
    val contentId: String?,
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val errorCode: String?,
    val exceptionClass: String?,
    val causeClass: String?,
    val causeMessage: String?,
    val videoCodec: String?,
    val mimeType: String?,
)

internal object PlaybackFailureNotes {
    @Volatile
    private var current: PlaybackFailureDetails? = null

    fun note(details: PlaybackFailureDetails) {
        current = details
    }

    fun clear() {
        current = null
    }

    fun takeMatching(message: String): PlaybackFailureDetails? {
        val note = current ?: return null
        if (note.message != message) return null
        current = null
        return note
    }
}

internal fun scrubPlaybackText(value: String?): String? {
    val cleaned = value
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.replace(whitespace, " ")
        ?.trim()
        .orEmpty()
    if (cleaned.isEmpty()) return null
    return magnetPattern.replace(urlPattern.replace(cleaned, "[url]"), "[url]").take(500)
}

internal fun playbackReportHost(url: String?): String? {
    val raw = url?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val afterScheme = raw.substringAfter("://", missingDelimiterValue = "")
    if (afterScheme.isEmpty()) return null
    val authority = afterScheme.substringBefore('/').substringBefore('?')
    val host = if (authority.startsWith("[")) {
        authority.substringAfter("[").substringBefore("]")
    } else {
        authority.substringBefore(":")
    }.trim().lowercase()
    if (host.isEmpty() || host in loopbackHosts) return null
    return host.take(200)
}

internal fun PlaybackFailureReport.summary(): String {
    val detail = groupingText(message)
    val code = errorCode?.takeIf { it.isNotBlank() }
    return if (code != null) "Playback failure [$code] $detail" else "Playback failure: $detail"
}

internal fun PlaybackFailureReport.fingerprint(): List<String> {
    val kind = errorCode?.takeIf { it.isNotBlank() } ?: groupingText(message)
    return listOf("playback-failure", engine ?: "unknown", kind)
}

internal expect fun reportPlaybackFailure(report: PlaybackFailureReport)

private fun groupingText(message: String): String =
    scrubPlaybackText(message)
        .orEmpty()
        .lowercase()
        .take(80)
        .ifBlank { "unknown" }

private val whitespace = Regex("\\s+")
private val urlPattern = Regex("""[a-z][a-z0-9+.-]*://\S+""", RegexOption.IGNORE_CASE)
private val magnetPattern = Regex("""magnet:\?\S+""", RegexOption.IGNORE_CASE)
private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1", "0.0.0.0")
