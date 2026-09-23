package com.nuvio.app.features.autosync

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal object AutoSyncPreferencesDesktop {
    private val store = DesktopStorage.store("nuvio_autosync_settings")

    fun initialize() {
        AutoSyncPreferencesRepository.installPersistence(
            load = { store.getBoolean(ProfileScopedKey.of("preferred_subtitle_auto_sync_on_start")) },
            save = { enabled ->
                store.putBoolean(ProfileScopedKey.of("preferred_subtitle_auto_sync_on_start"), enabled)
            },
            loadAggressiveMode = { store.getBoolean(ProfileScopedKey.of("auto_sync_aggressive_mode")) },
            saveAggressiveMode = { enabled ->
                store.putBoolean(ProfileScopedKey.of("auto_sync_aggressive_mode"), enabled)
            },
        )
    }
}
