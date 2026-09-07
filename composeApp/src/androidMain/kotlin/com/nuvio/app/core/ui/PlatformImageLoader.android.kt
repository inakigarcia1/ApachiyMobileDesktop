package com.nuvio.app.core.ui

import android.os.Build
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.nuvio.app.features.addons.AddonHttpClientProvider

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder =
    components {
        add(
            OkHttpNetworkFetcherFactory(
                callFactory = { AddonHttpClientProvider.get() },
                cacheStrategy = { CacheControlCacheStrategy() },
            ),
        )
        if (Build.VERSION.SDK_INT >= 28) {
            add(AnimatedImageDecoder.Factory())
        } else {
            add(GifDecoder.Factory())
        }
    }
