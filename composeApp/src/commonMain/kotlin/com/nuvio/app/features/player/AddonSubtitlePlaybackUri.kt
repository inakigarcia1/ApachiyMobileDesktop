package com.nuvio.app.features.player

internal expect suspend fun prepareAddonSubtitlePlaybackUri(
    remoteUrl: String,
    sourceHeaders: Map<String, String> = emptyMap(),
    cacheKey: String = remoteUrl,
): String?

internal fun requiresAuthenticatedSubtitleDownload(url: String): Boolean =
    url.contains("/apachiy/subtitles/proxy/", ignoreCase = true)

internal suspend fun resolvePlaybackSubtitleUri(
    remoteUrl: String,
    sourceHeaders: Map<String, String> = emptyMap(),
    cacheKey: String = remoteUrl,
): String? {
    val prepared = prepareAddonSubtitlePlaybackUri(
        remoteUrl = remoteUrl,
        sourceHeaders = sourceHeaders,
        cacheKey = cacheKey,
    )
    return when {
        prepared != null -> prepared
        requiresAuthenticatedSubtitleDownload(remoteUrl) -> null
        else -> remoteUrl
    }
}

internal fun sniffSubtitleFileExtension(body: String, sourceUrl: String): String {
    val text = body.replace("\uFEFF", "").trimStart()
    if (text.startsWith("WEBVTT", ignoreCase = true)) return "vtt"
    val head = text.take(4_000)
    if (
        head.startsWith("[Script Info]", ignoreCase = true) ||
        head.contains("[V4+ Styles]", ignoreCase = true) ||
        head.contains("[V4 Styles]", ignoreCase = true) ||
        Regex("""(?im)^\s*Dialogue:""").containsMatchIn(head)
    ) {
        return "ass"
    }
    if (
        Regex(
            """(?m)^\d+\s*\r?\n\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}"""
        ).containsMatchIn(text.take(800))
    ) {
        return "srt"
    }
    return guessSubtitleExtensionFromUrl(sourceUrl)
}

internal fun guessSubtitleExtensionFromUrl(url: String): String {
    val path = url.substringBefore('?').substringBefore('#').trimEnd('/').lowercase()
    return when {
        path.endsWith(".vtt") || path.endsWith(".webvtt") -> "vtt"
        path.endsWith(".ass") || path.endsWith(".ssa") -> "ass"
        path.endsWith(".srt") -> "srt"
        path.endsWith(".ttml") || path.endsWith(".dfxp") -> "ttml"
        else -> "vtt"
    }
}

internal data class RawHttpUrlParts(
    val scheme: String,
    val host: String,
    val port: Int,
    val encodedPath: String,
    val encodedQuery: String?,
) {
    val hasEncodedSlashInPath: Boolean
        get() = encodedPath.contains("%2F", ignoreCase = true)
    val pathSegmentCount: Int
        get() = encodedPath.trim('/').split('/').size
}

internal fun parseRawHttpUrlParts(url: String): RawHttpUrlParts? {
    val trimmed = url.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val afterScheme = trimmed.substring(schemeEnd + 3)
    val pathIndex = afterScheme.indexOf('/')
    val hostPort = if (pathIndex < 0) afterScheme.substringBefore('#') else afterScheme.substring(0, pathIndex)
    if (hostPort.isBlank()) return null
    val pathAndQuery = if (pathIndex < 0) {
        "/"
    } else {
        afterScheme.substring(pathIndex).substringBefore('#')
    }
    val queryIndex = pathAndQuery.indexOf('?')
    val encodedPath = if (queryIndex < 0) pathAndQuery else pathAndQuery.substring(0, queryIndex)
    val encodedQuery = if (queryIndex < 0) null else pathAndQuery.substring(queryIndex + 1).takeIf { it.isNotEmpty() }
    val ipv6End = hostPort.indexOf(']')
    val colon = if (ipv6End >= 0) hostPort.indexOf(':', ipv6End) else hostPort.lastIndexOf(':')
    val host: String
    val port: Int
    if (colon > 0) {
        host = hostPort.substring(0, colon).trim('[', ']')
        port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
    } else {
        host = hostPort.trim('[', ']')
        port = if (scheme == "https") 443 else 80
    }
    if (host.isBlank() || encodedPath.isEmpty()) return null
    return RawHttpUrlParts(
        scheme = scheme,
        host = host,
        port = port,
        encodedPath = encodedPath,
        encodedQuery = encodedQuery,
    )
}

internal fun buildAddonSubtitleCacheKey(
    remoteUrl: String,
    videoHash: String? = null,
    videoSize: Long? = null,
    filename: String? = null,
): String = buildString {
    append(remoteUrl.trim())
    videoHash?.trim()?.takeIf { it.isNotBlank() }?.let { append("|hash=").append(it) }
    videoSize?.takeIf { it > 0L }?.let { append("|size=").append(it) }
    filename?.trim()?.takeIf { it.isNotBlank() }?.let { append("|file=").append(it) }
}
