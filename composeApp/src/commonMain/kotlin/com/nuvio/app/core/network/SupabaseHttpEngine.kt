package com.nuvio.app.core.network

import io.ktor.client.engine.HttpClientEngine

internal expect fun createSupabaseHttpEngine(): HttpClientEngine?
