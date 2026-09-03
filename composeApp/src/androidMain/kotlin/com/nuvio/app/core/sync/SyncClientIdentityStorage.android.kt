package com.nuvio.app.core.sync

import android.content.Context
import android.content.SharedPreferences
import java.io.File

actual object SyncClientIdentityStorage {
    private const val preferencesName = "apachiy_installation"
    private const val clientIdKey = "client_instance_id"
    private const val registeredDeviceIdKey = "registered_device_id"
    private const val clientIdFileName = "apachiy_installation_id"

    private var preferences: SharedPreferences? = null
    private var clientIdFile: File? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        clientIdFile = File(context.noBackupFilesDir, clientIdFileName)
    }

    actual fun loadClientId(): String? {
        val fromFile = clientIdFile
            ?.takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (fromFile != null) return fromFile
        return preferences?.getString(clientIdKey, null)
    }

    actual fun saveClientId(clientId: String) {
        runCatching {
            clientIdFile?.writeText(clientId)
        }
        preferences
            ?.edit()
            ?.putString(clientIdKey, clientId)
            ?.apply()
    }

    actual fun loadRegisteredDeviceId(): Long? {
        val prefs = preferences ?: return null
        if (!prefs.contains(registeredDeviceIdKey)) return null
        return prefs.getLong(registeredDeviceIdKey, 0L).takeIf { it > 0L }
    }

    actual fun saveRegisteredDeviceId(deviceId: Long) {
        preferences
            ?.edit()
            ?.putLong(registeredDeviceIdKey, deviceId)
            ?.apply()
    }

    actual fun clearRegisteredDeviceId() {
        preferences
            ?.edit()
            ?.remove(registeredDeviceIdKey)
            ?.apply()
    }
}
