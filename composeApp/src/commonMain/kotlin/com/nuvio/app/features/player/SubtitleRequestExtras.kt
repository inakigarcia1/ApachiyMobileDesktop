package com.nuvio.app.features.player

import com.nuvio.app.features.addons.encodeAddonPathSegment

fun buildSubtitleExtraPathSegment(
    videoHash: String? = null,
    videoSize: Long? = null,
    filename: String? = null,
    hasEmbeddedSpanish: Boolean? = null,
): String? {
    val params = buildList {
        videoHash?.trim()?.takeIf { it.isNotBlank() }?.let { add("videoHash=$it") }
        videoSize?.takeIf { it > 0L }?.let { add("videoSize=$it") }
        filename?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
            add("filename=${value.encodeAddonPathSegment()}")
        }
        hasEmbeddedSpanish?.let { add("hasEmbeddedSpanish=$it") }
    }
    return params.joinToString("&").takeIf { it.isNotEmpty() }
}
