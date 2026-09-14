package com.nuvio.app.features.addons

import com.nuvio.app.core.network.rewriteLocalDevUrl

internal suspend fun fetchAddonResponseText(
    url: String,
    forceRefresh: Boolean = false,
): String {
    val resolvedUrl = rewriteLocalDevUrl(url) ?: url
    return if (forceRefresh) {
        httpGetTextWithHeaders(
            url = resolvedUrl,
            headers = mapOf("Cache-Control" to "no-cache"),
        )
    } else {
        httpGetText(resolvedUrl)
    }
}
