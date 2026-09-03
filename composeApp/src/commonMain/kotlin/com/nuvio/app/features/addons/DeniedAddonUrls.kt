package com.nuvio.app.features.addons

import io.ktor.http.Url

object DeniedAddonUrls {
    private val deniedHosts = setOf(
        "v3-cinemeta.strem.io",
        "cinemeta.strem.io",
        "opensubtitles-v3.strem.io",
        "opensubtitles.strem.io",
    )

    fun isDeniedAddonUrl(url: String): Boolean {
        return try {
            val trimmed = url.trim().trimEnd('/')
            val queryStart = trimmed.indexOf('?')
            val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
            val withoutManifest = if (path.endsWith("/manifest.json", ignoreCase = true)) {
                path.dropLast("/manifest.json".length).trimEnd('/')
            } else {
                path.trimEnd('/')
            }
            val withScheme = when {
                withoutManifest.startsWith("http://", ignoreCase = true) ||
                    withoutManifest.startsWith("https://", ignoreCase = true) -> withoutManifest
                withoutManifest.startsWith("stremio://", ignoreCase = true) ->
                    "https://${withoutManifest.removePrefix("stremio://")}"
                else -> "https://$withoutManifest"
            }
            val host = Url(withScheme).host.lowercase()
            host.isNotBlank() && host in deniedHosts
        } catch (_: Exception) {
            false
        }
    }
}
