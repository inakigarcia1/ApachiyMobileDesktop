package com.nuvio.app.core.network

import com.nuvio.app.features.addons.DesktopAddonHttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun createAuthenticatedNetworkHttpClient(): HttpClient =
    HttpClient(OkHttp) {
        engine {
            preconfigured = DesktopAddonHttpClientProvider.get()
        }
        installApachiyAddonAuth()
        expectSuccess = false
    }
