package com.nuvio.app.features.network

internal actual fun isDevelopmentBuild(): Boolean = TorboxSpeedTestHarness.isDevelopmentBuild()
