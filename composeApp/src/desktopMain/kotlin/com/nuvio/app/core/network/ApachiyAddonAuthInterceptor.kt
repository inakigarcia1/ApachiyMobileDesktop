package com.nuvio.app.core.network

import com.nuvio.app.core.account.AccountStatusRepository
import com.nuvio.app.core.auth.AuthRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class ApachiyAddonAuthInterceptor(
    private val apachiyApiHost: String = ApachiyAddonAuth.hostFromBaseUrl(ApachiyConfig.API_BASE_URL),
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!ApachiyAddonAuth.shouldAttachAuth(request.url.host, apachiyApiHost)) {
            return chain.proceed(request)
        }

        val initialRequest = withAccessToken(request, ApachiyAddonAuth.currentAccessToken())
        val response = chain.proceed(initialRequest)
        if (response.code == 403 && response.isAccountInactive()) {
            AccountStatusRepository.markInactive()
            return response
        }
        if (response.code != 401) {
            return response
        }

        response.close()
        val refreshed = runBlocking {
            AuthRepository.refreshCurrentSession()
        }
        if (!refreshed) {
            return chain.proceed(initialRequest)
        }

        val retryRequest = withAccessToken(request, ApachiyAddonAuth.currentAccessToken())
        val retryResponse = chain.proceed(retryRequest)
        if (retryResponse.code == 403 && retryResponse.isAccountInactive()) {
            AccountStatusRepository.markInactive()
        }
        return retryResponse
    }

    private fun Response.isAccountInactive(): Boolean {
        val body = peekBody(ACCOUNT_INACTIVE_PEEK_BYTES).string()
        return ApachiyAddonAuth.isAccountInactiveBody(body)
    }

    private fun withAccessToken(request: Request, token: String?): Request {
        if (token.isNullOrBlank()) return request
        return request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
    }

    companion object {
        private const val ACCOUNT_INACTIVE_PEEK_BYTES = 1024L
    }
}
