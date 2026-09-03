package com.nuvio.app.core.network

import com.nuvio.app.features.addons.AddonHttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun createAuthenticatedNetworkHttpClient(): HttpClient =
    HttpClient(OkHttp) {
        engine {
            preconfigured = AddonHttpClientProvider.get()
        }
    }
