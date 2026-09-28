package com.nuvio.app.features.network

import kotlin.native.Platform

internal actual fun isDevelopmentBuild(): Boolean = Platform.isDebugBinary
