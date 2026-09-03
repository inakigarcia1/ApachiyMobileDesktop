package com.nuvio.app.core.sync

import com.nuvio.app.core.storage.DesktopStorage

internal actual object SyncClientIdentityStorage {
    private const val clientIdKey = "client_instance_id"
    private const val registeredDeviceIdKey = "registered_device_id"
    private val store = DesktopStorage.store("apachiy_installation")

    actual fun loadClientId(): String? =
        store.getString(clientIdKey)

    actual fun saveClientId(clientId: String) {
        store.putString(clientIdKey, clientId)
    }

    actual fun loadRegisteredDeviceId(): Long? =
        store.getString(registeredDeviceIdKey)?.toLongOrNull()?.takeIf { it > 0L }

    actual fun saveRegisteredDeviceId(deviceId: Long) {
        store.putString(registeredDeviceIdKey, deviceId.toString())
    }

    actual fun clearRegisteredDeviceId() {
        store.remove(registeredDeviceIdKey)
    }
}
