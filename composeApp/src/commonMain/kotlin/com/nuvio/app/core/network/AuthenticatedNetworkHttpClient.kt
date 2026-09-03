package com.nuvio.app.core.network

import io.ktor.client.HttpClient

expect fun createAuthenticatedNetworkHttpClient(): HttpClient
