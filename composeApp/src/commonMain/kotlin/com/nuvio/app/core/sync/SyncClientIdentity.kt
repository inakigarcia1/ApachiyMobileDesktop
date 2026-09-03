package com.nuvio.app.core.sync

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val UUID_V4_REGEX = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    RegexOption.IGNORE_CASE,
)
private const val ORIGIN_CLIENT_ID_PARAM = "p_origin_client_id"

object SyncClientIdentity {
    private var cachedClientId: String? = null

    fun currentClientId(): String {
        cachedClientId?.let { return it }

        val stored = SyncClientIdentityStorage.loadClientId()
            ?.trim()
            ?.takeIf(::isValidUuidV4)
        if (stored != null) {
            cachedClientId = stored
            return stored
        }

        val generated = generateClientId()
        SyncClientIdentityStorage.saveClientId(generated)
        cachedClientId = generated
        return generated
    }

    fun saveRegisteredDeviceId(deviceId: Long) {
        SyncClientIdentityStorage.saveRegisteredDeviceId(deviceId)
    }

    fun loadRegisteredDeviceId(): Long? =
        SyncClientIdentityStorage.loadRegisteredDeviceId()

    fun clearRegisteredDeviceId() {
        SyncClientIdentityStorage.clearRegisteredDeviceId()
    }

    @OptIn(ExperimentalUuidApi::class)
    internal fun generateClientId(): String = Uuid.random().toString()

    internal fun isValidUuidV4(value: String): Boolean = UUID_V4_REGEX.matches(value)
}

internal fun JsonObjectBuilder.putSyncOriginClientId() {
    put(ORIGIN_CLIENT_ID_PARAM, SyncClientIdentity.currentClientId())
}

internal expect object SyncClientIdentityStorage {
    fun loadClientId(): String?
    fun saveClientId(clientId: String)
    fun loadRegisteredDeviceId(): Long?
    fun saveRegisteredDeviceId(deviceId: Long)
    fun clearRegisteredDeviceId()
}
