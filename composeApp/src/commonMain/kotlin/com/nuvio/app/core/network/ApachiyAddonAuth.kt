package com.nuvio.app.core.network

import io.github.jan.supabase.auth.auth
import io.ktor.http.Url

object ApachiyAddonAuth {
    const val ACCOUNT_INACTIVE_ERROR = "account_inactive"

    fun hostFromBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return runCatching { Url(withScheme).host }.getOrNull().orEmpty()
    }

    fun shouldAttachAuth(requestHost: String, apiHost: String): Boolean {
        if (apiHost.isBlank() || requestHost.isBlank()) return false
        return requestHost.equals(apiHost, ignoreCase = true)
    }

    fun isAccountInactiveBody(body: String): Boolean =
        body.contains(ACCOUNT_INACTIVE_ERROR)

    fun currentAccessToken(): String? =
        runCatching { SupabaseProvider.client.auth.currentAccessTokenOrNull() }.getOrNull()
}
