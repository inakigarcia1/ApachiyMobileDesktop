package com.nuvio.app.features.search

import com.nuvio.app.core.device.ApachiyDeviceApi
import com.nuvio.app.core.device.SearchSuggestionDto
import com.nuvio.app.core.network.ApachiyConfig
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.auth.auth

internal suspend fun loadSearchSuggestions(query: String): List<SearchSuggestionDto> {
    if (ApachiyConfig.API_BASE_URL.isBlank()) return emptyList()
    val token = runCatching { SupabaseProvider.client.auth.currentAccessTokenOrNull() }
        .getOrNull()
        ?.trim()
        .orEmpty()
    if (token.isEmpty()) return emptyList()
    val response = runCatching { ApachiyDeviceApi.getSearchSuggestions(token, query) }.getOrNull()
        ?: return emptyList()
    if (response.status !in 200..299) return emptyList()
    return ApachiyDeviceApi.decodeSearchSuggestions(response.body)
}
