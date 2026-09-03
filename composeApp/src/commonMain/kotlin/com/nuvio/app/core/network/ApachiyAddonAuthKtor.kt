package com.nuvio.app.core.network

import com.nuvio.app.core.account.AccountStatusRepository
import com.nuvio.app.core.auth.AuthRepository
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.takeFrom

fun HttpClientConfig<*>.installApachiyAddonAuth() {
    val apiHost = ApachiyAddonAuth.hostFromBaseUrl(ApachiyConfig.API_BASE_URL)
    install(
        createClientPlugin("ApachiyAddonAuth") {
            on(Send) { request ->
                if (ApachiyAddonAuth.shouldAttachAuth(request.url.host, apiHost)) {
                    ApachiyAddonAuth.currentAccessToken()?.takeIf { it.isNotBlank() }?.let { token ->
                        request.headers.append(HttpHeaders.Authorization, "Bearer $token")
                    }
                }
                var call = proceed(request)
                if (!ApachiyAddonAuth.shouldAttachAuth(request.url.host, apiHost)) {
                    return@on call
                }
                val status = call.response.status.value
                if (status == 403) {
                    AccountStatusRepository.markInactive()
                    return@on call
                }
                if (status != 401) {
                    return@on call
                }
                val refreshed = AuthRepository.refreshCurrentSession()
                if (!refreshed) {
                    return@on call
                }
                val retry = HttpRequestBuilder().takeFrom(request)
                retry.headers.remove(HttpHeaders.Authorization)
                ApachiyAddonAuth.currentAccessToken()?.takeIf { it.isNotBlank() }?.let { token ->
                    retry.headers.append(HttpHeaders.Authorization, "Bearer $token")
                }
                call = proceed(retry)
                if (call.response.status.value == 403) {
                    AccountStatusRepository.markInactive()
                }
                call
            }
        },
    )
}
