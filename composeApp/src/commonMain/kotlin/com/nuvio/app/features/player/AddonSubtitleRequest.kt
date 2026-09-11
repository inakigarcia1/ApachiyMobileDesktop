package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.player.embedded.EmbeddedSubtitleReference
import com.nuvio.app.features.player.embedded.isApachiySubtitleAddon

internal object AddonSubtitleRequest {
    fun shouldPostEmbeddedReference(
        manifest: AddonManifest,
        subtitleUrl: String,
        reference: EmbeddedSubtitleReference?,
    ): Boolean = reference != null && isApachiySubtitleAddon(manifest, subtitleUrl)

    fun shouldFallbackPostToGet(status: Int): Boolean = status !in 200..299

    fun buildMultipartBody(reference: EmbeddedSubtitleReference): Pair<String, String> {
        val boundary = "----NuvioEmbedded${reference.bytes.size}${reference.filename.hashCode().toUInt()}"
        val safeName = reference.filename.substringAfterLast('/').ifBlank { "embedded.srt" }
        val body = buildString {
            append("--")
            append(boundary)
            append("\r\n")
            append("Content-Disposition: form-data; name=\"reference\"; filename=\"")
            append(safeName)
            append("\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
            append(reference.bytes.decodeToString())
            append("\r\n")
            val lang = reference.language?.trim().orEmpty()
            if (lang.isNotEmpty()) {
                append("--")
                append(boundary)
                append("\r\n")
                append("Content-Disposition: form-data; name=\"referenceLang\"\r\n\r\n")
                append(lang)
                append("\r\n")
            }
            append("--")
            append(boundary)
            append("--\r\n")
        }
        return "multipart/form-data; boundary=$boundary" to body
    }
}
