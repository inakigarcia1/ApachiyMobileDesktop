package com.nuvio.app.features.network

import com.nuvio.app.features.updater.AppUpdaterPlatform

internal actual fun isDevelopmentBuild(): Boolean = AppUpdaterPlatform.isDebugBuild
