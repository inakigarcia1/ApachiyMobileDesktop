package com.nuvio.app.core.build

import com.nuvio.app.features.updater.AppUpdaterPlatform

object ApachiyProductSettings {
    val operatorSettingsVisible: Boolean
        get() = AppUpdaterPlatform.isDebugBuild
}
