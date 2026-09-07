package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.ktor3.KtorNetworkFetcherFactory

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder =
    components {
        add(
            KtorNetworkFetcherFactory(
                cacheStrategy = { CacheControlCacheStrategy() },
            ),
        )
    }
