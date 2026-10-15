package com.nuvio.app.features.streams

import kotlinx.serialization.Serializable

internal const val ApachiyStreamAddonPrefix = "addon:com.apachiy.addon:"

@Serializable
internal data class StreamBlacklistRequest(
    val mediaType: String,
    val mediaId: String,
    val infoHash: String,
    val season: Int? = null,
    val episode: Int? = null,
)

internal fun streamBlacklistTarget(
    addonId: String,
    bingeGroup: String?,
    type: String,
    videoId: String,
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): StreamBlacklistRequest? {
    if (!addonId.startsWith(ApachiyStreamAddonPrefix)) return null
    val hash = bingeGroup
        ?.split('|')
        ?.lastOrNull()
        ?.trim()
        ?.lowercase()
        ?.takeIf { value -> value.length == 40 && value.all { it in '0'..'9' || it in 'a'..'f' } }
        ?: return null
    val mediaType = when (type.trim().lowercase()) {
        "movie" -> "movie"
        "series", "tv", "show", "tvshow" -> "series"
        else -> return null
    }
    val mediaId = if (mediaType == "series") {
        imdbId(parentMetaId) ?: imdbId(videoId.substringBefore(':'))
    } else {
        imdbId(videoId)
    } ?: return null
    if (mediaType == "series" && (seasonNumber == null || episodeNumber == null || seasonNumber < 1 || episodeNumber < 1)) {
        return null
    }
    return StreamBlacklistRequest(
        mediaType = mediaType,
        mediaId = mediaId,
        infoHash = hash,
        season = if (mediaType == "series") seasonNumber else null,
        episode = if (mediaType == "series") episodeNumber else null,
    )
}

internal fun AddonStreamGroup.withoutApachiyHash(infoHash: String): AddonStreamGroup {
    if (!addonId.startsWith(ApachiyStreamAddonPrefix)) return this
    val hash = infoHash.lowercase()
    return copy(
        streams = streams.filter { stream ->
            stream.behaviorHints.bingeGroup
                ?.split('|')
                ?.lastOrNull()
                ?.trim()
                ?.lowercase() != hash
        },
    )
}

private fun imdbId(value: String): String? {
    val id = value.trim().lowercase()
    if (id.length < 3 || !id.startsWith("tt") || !id.drop(2).all(Char::isDigit)) return null
    return id
}
