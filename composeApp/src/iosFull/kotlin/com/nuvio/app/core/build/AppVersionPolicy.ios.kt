package com.nuvio.app.core.build

actual object AppVersionPolicy {
    actual val displayVersionName: String = AppVersionConfig.VERSION_NAME
    actual val displayVersionCode: Int = AppVersionConfig.VERSION_CODE
    actual val basedOnVersionName: String? = null
    actual val userAgentAppName: String = "ApachiyMobile"
    actual val clientHeaderPrefix: String = "apachiy-mobile"
    actual val deviceClientName: String = "Apachiy Mobile"
}
