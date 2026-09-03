package com.nuvio.app.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

internal actual fun createSupabaseHttpEngine(): HttpClientEngine? =
    OkHttp.create {
        config {
            dns(IPv4FirstDns())
            retryOnConnectionFailure(true)
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(20, TimeUnit.SECONDS)
            writeTimeout(20, TimeUnit.SECONDS)
        }
    }
