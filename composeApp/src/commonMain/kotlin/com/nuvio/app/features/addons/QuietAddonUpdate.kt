package com.nuvio.app.features.addons

internal data class QuietAddonRow(
    val url: String,
    val enabled: Boolean,
    val name: String?,
    val manifestToken: String?,
)

internal data class QuietRemoteAddon(
    val url: String,
    val enabled: Boolean,
    val name: String?,
)

internal sealed interface QuietManifestResult {
    data class Ready(val token: String) : QuietManifestResult
    data object Failed : QuietManifestResult
}

internal fun AddonManifest.quietToken(): String = listOf(
    id,
    version,
    name,
    transportUrl,
    catalogs.joinToString(",") { "${it.type}:${it.id}:${it.name}" },
    resources.joinToString(",") { "${it.name}:${it.types.joinToString("|")}" },
).joinToString("\u0000")

/**
 * Next visible addon rows, or null when the screen must stay as it is.
 * An empty or failed remote list never replaces local addons.
 * A failed manifest keeps the previous one. A new URL without a manifest stays off screen.
 */
internal fun resolveQuietAddonRows(
    current: List<QuietAddonRow>,
    remote: List<QuietRemoteAddon>?,
    manifests: Map<String, QuietManifestResult>,
): List<QuietAddonRow>? {
    if (remote.isNullOrEmpty()) return null
    val currentByUrl = current.associateBy { it.url }
    val next = remote.mapNotNull { row ->
        val name = row.name?.takeIf { it.isNotBlank() }
        when (val fetched = manifests[row.url]) {
            is QuietManifestResult.Ready -> QuietAddonRow(
                url = row.url,
                enabled = row.enabled,
                name = name,
                manifestToken = fetched.token,
            )
            QuietManifestResult.Failed, null -> {
                val previous = currentByUrl[row.url]
                val token = previous?.manifestToken ?: return@mapNotNull null
                previous.copy(enabled = row.enabled, name = name, manifestToken = token)
            }
        }
    }
    if (next.isEmpty() || next == current) return null
    return next
}
