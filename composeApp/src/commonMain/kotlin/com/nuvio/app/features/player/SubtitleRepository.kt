package com.nuvio.app.features.player

import com.nuvio.app.core.network.rewriteLocalDevUrl
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.player.agentqa.AgentQa
import com.nuvio.app.features.player.embedded.isApachiySubtitleAddon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_no_subtitles_found
import nuvio.composeapp.generated.resources.player_addon_subtitle_display_format
import org.jetbrains.compose.resources.getString

object SubtitleRepository {
    private val json = Json { ignoreUnknownKeys = true }

    private val _addonSubtitles = MutableStateFlow<List<AddonSubtitle>>(emptyList())
    val addonSubtitles: StateFlow<List<AddonSubtitle>> = _addonSubtitles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clear() {
        _addonSubtitles.value = emptyList()
        _isLoading.value = false
        _error.value = null
    }

    suspend fun fetchApachiySubtitles(
        type: String,
        videoId: String,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
    ): List<AddonSubtitle> {
        val requestType = canonicalSubtitleType(type)
        val apachiyAddon = AddonRepository.uiState.value.addons.enabledAddons().firstOrNull { addon ->
            val manifest = addon.manifest ?: return@firstOrNull false
            val subtitleResource = manifest.resources.find { it.name.isSubtitleResourceName() } ?: return@firstOrNull false
            subtitleResource.supportsSubtitleType(requestType, videoId) && isApachiySubtitleAddon(manifest)
        }
        val manifest = apachiyAddon?.manifest
        if (apachiyAddon == null || manifest == null) {
            _addonSubtitles.value = emptyList()
            _isLoading.value = false
            return emptyList()
        }
        val extraPathSegment = if (AgentQa.enabled) {
            null
        } else {
            buildSubtitleExtraPathSegment(
                videoHash = videoHash,
                videoSize = videoSize,
                filename = filename,
                hasEmbeddedSpanish = null,
            )
        }
        val subtitleUrl = buildAddonResourceUrl(
            manifestUrl = manifest.transportUrl,
            resource = "subtitles",
            type = requestType,
            id = videoId,
            extraPathSegment = extraPathSegment,
        )
        _isLoading.value = true
        _error.value = null
        val posted = withTimeoutOrNull(30_000L) {
            runCatching {
                httpRequestRaw(
                    method = "GET",
                    url = rewriteLocalDevUrl(subtitleUrl) ?: subtitleUrl,
                    headers = mapOf("Accept" to "application/json"),
                    body = "",
                )
            }.getOrNull()
        }
        if (posted == null || posted.status !in 200..299) {
            _addonSubtitles.value = emptyList()
            _isLoading.value = false
            _error.value = getString(Res.string.compose_player_no_subtitles_found)
            return emptyList()
        }
        val parsed = parseAddonSubtitleListing(posted.body, manifest.id, apachiyAddon.displayTitle)
        _addonSubtitles.value = parsed
        if (parsed.isEmpty()) {
            _error.value = getString(Res.string.compose_player_no_subtitles_found)
        }
        _isLoading.value = false
        return parsed
    }

    private suspend fun parseAddonSubtitleListing(
        response: String,
        manifestId: String?,
        addonTitle: String,
    ): List<AddonSubtitle> {
        val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull() ?: return emptyList()
        val subtitlesArray = root["subtitles"]?.jsonArray ?: return emptyList()
        val addonSubs = mutableListOf<AddonSubtitle>()
        for (element in subtitlesArray) {
            val obj = element.jsonObject
            val id = obj.stringValue("id") ?: "${manifestId}_${addonSubs.size}"
            val rawUrl = obj.stringValue("url") ?: continue
            val url = rewriteLocalDevUrl(rawUrl) ?: rawUrl
            val rawLang = obj.subtitleLanguage() ?: "unknown"
            val normalizedLang = normalizeLanguageCode(rawLang) ?: rawLang
            addonSubs.add(
                AddonSubtitle(
                    id = id,
                    url = url,
                    language = normalizedLang,
                    display = getString(
                        Res.string.player_addon_subtitle_display_format,
                        getLanguageLabelForCode(rawLang),
                        addonTitle,
                    ),
                    addonName = addonTitle,
                )
            )
        }
        return addonSubs
    }
}

private fun canonicalSubtitleType(type: String): String =
    if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()

private fun String.isSubtitleResourceName(): Boolean =
    equals("subtitles", ignoreCase = true) || equals("subtitle", ignoreCase = true)

private fun AddonResource.supportsSubtitleType(type: String, videoId: String): Boolean {
    val canonical = canonicalSubtitleType(type)
    val typeMatches = types.isEmpty() || types.any { canonicalSubtitleType(it).equals(canonical, ignoreCase = true) }
    if (!typeMatches) return false
    return idPrefixes.isEmpty() || idPrefixes.any { prefix -> videoId.startsWith(prefix) }
}

private fun JsonObject.subtitleLanguage(): String? =
    stringValue("lang")
        ?: stringValue("language")
        ?: stringValue("languageCode")
        ?: stringValue("locale")
        ?: stringValue("label")

private fun JsonObject.stringValue(name: String): String? =
    this[name]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }
