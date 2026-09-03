package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.nuvio.app.features.addons.DesktopAddonHttpClientProvider

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    return components {
        add(
            OkHttpNetworkFetcherFactory(
                callFactory = { DesktopAddonHttpClientProvider.get() },
                cacheStrategy = { CacheControlCacheStrategy() },
            ),
        )
        add(SkiaGifDecoder.Factory())
    }
}
