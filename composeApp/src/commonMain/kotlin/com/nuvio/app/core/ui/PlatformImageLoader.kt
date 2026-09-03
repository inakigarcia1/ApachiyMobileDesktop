package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.map.Mapper
import coil3.request.Options

internal expect fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder

internal fun normalizeLoadableImageUrl(data: String): String? {
    val trimmed = data.trim()
    return if (trimmed.startsWith("//")) "https:$trimmed" else null
}

internal class ProtocolRelativeImageUrlMapper : Mapper<String, String> {
    override fun map(data: String, options: Options): String? = normalizeLoadableImageUrl(data)
}
