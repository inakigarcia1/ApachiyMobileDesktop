package com.nuvio.app.core.network

import com.nuvio.app.core.storage.DesktopStorage
import com.russhwolf.settings.Settings
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.SettingsSessionManager

internal actual fun createPlatformAuthSessionManager(): SessionManager? =
    createDesktopAuthSessionManager()

private fun createDesktopAuthSessionManager(): SessionManager {
    val store = DesktopStorage.store("nuvio_supabase_auth")
    val settings = DesktopStoreSettings(store)
    migrateLegacySessionIfNeeded(settings)
    return SettingsSessionManager(settings)
}

/**
 * Copy a previously saved Supabase session from the default Java Preferences
 * store into APPDATA so upgrades / Preference node changes don't force re-login.
 */
private fun migrateLegacySessionIfNeeded(target: Settings) {
    if (target.hasKey(SettingsSessionManager.SETTINGS_KEY)) return
    val legacy = runCatching { Settings() }.getOrNull() ?: return
    val session = legacy.getStringOrNull(SettingsSessionManager.SETTINGS_KEY) ?: return
    target.putString(SettingsSessionManager.SETTINGS_KEY, session)
}

private class DesktopStoreSettings(
    private val store: DesktopStorage.Store,
) : Settings {
    private val knownKeys = linkedSetOf<String>().also { keys ->
        if (store.contains(SettingsSessionManager.SETTINGS_KEY)) {
            keys += SettingsSessionManager.SETTINGS_KEY
        }
    }

    override val keys: Set<String>
        get() = knownKeys.toSet()

    override val size: Int
        get() = knownKeys.size

    override fun clear() {
        knownKeys.toList().forEach(::remove)
    }

    override fun remove(key: String) {
        store.remove(key)
        knownKeys.remove(key)
    }

    override fun hasKey(key: String): Boolean =
        store.contains(key)

    override fun putInt(key: String, value: Int) {
        store.putInt(key, value)
        knownKeys += key
    }

    override fun getInt(key: String, defaultValue: Int): Int =
        store.getInt(key) ?: defaultValue

    override fun getIntOrNull(key: String): Int? =
        store.getInt(key)

    override fun putLong(key: String, value: Long) {
        store.putString(key, value.toString())
        knownKeys += key
    }

    override fun getLong(key: String, defaultValue: Long): Long =
        store.getString(key)?.toLongOrNull() ?: defaultValue

    override fun getLongOrNull(key: String): Long? =
        store.getString(key)?.toLongOrNull()

    override fun putString(key: String, value: String) {
        store.putString(key, value)
        knownKeys += key
    }

    override fun getString(key: String, defaultValue: String): String =
        store.getString(key) ?: defaultValue

    override fun getStringOrNull(key: String): String? =
        store.getString(key)

    override fun putFloat(key: String, value: Float) {
        store.putFloat(key, value)
        knownKeys += key
    }

    override fun getFloat(key: String, defaultValue: Float): Float =
        store.getFloat(key) ?: defaultValue

    override fun getFloatOrNull(key: String): Float? =
        store.getFloat(key)

    override fun putDouble(key: String, value: Double) {
        store.putString(key, value.toString())
        knownKeys += key
    }

    override fun getDouble(key: String, defaultValue: Double): Double =
        store.getString(key)?.toDoubleOrNull() ?: defaultValue

    override fun getDoubleOrNull(key: String): Double? =
        store.getString(key)?.toDoubleOrNull()

    override fun putBoolean(key: String, value: Boolean) {
        store.putBoolean(key, value)
        knownKeys += key
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        store.getBoolean(key) ?: defaultValue

    override fun getBooleanOrNull(key: String): Boolean? =
        store.getBoolean(key)
}
