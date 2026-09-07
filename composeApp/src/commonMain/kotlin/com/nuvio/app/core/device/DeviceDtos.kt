package com.nuvio.app.core.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceRegistrationRequest(
    @SerialName("installationId") val installationId: String,
    @SerialName("platform") val platform: String,
    @SerialName("app") val app: String = "apachiy",
    @SerialName("appVersion") val appVersion: String,
    @SerialName("osVersion") val osVersion: String? = null,
    @SerialName("deviceModel") val deviceModel: String? = null,
)

@Serializable
data class DeviceRegistrationResponse(
    @SerialName("device_id") private val deviceIdSnake: Long = 0,
    @SerialName("deviceId") private val deviceIdCamel: Long = 0,
    @SerialName("created") val created: Boolean = false,
    @SerialName("revoked") val revoked: Boolean = false,
    @SerialName("last_login_at") val lastLoginAt: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
) {
    val deviceId: Long
        get() = deviceIdSnake.takeIf { it > 0L } ?: deviceIdCamel
}

@Serializable
data class DeviceSummaryDto(
    val id: Long = 0,
    @SerialName("deviceId") val deviceId: Long = 0,
    @SerialName("device_id") val deviceIdSnake: Long = 0,
    @SerialName("installationId") val installationId: String = "",
    @SerialName("installation_id") val installationIdSnake: String = "",
) {
    val resolvedDeviceId: Long
        get() = id.takeIf { it > 0L } ?: deviceId.takeIf { it > 0L } ?: deviceIdSnake

    val resolvedInstallationId: String
        get() = installationId.ifBlank { installationIdSnake }
}

@Serializable
data class DeviceListResponseDto(
    val devices: List<DeviceSummaryDto> = emptyList(),
    @SerialName("maxDevices") val maxDevices: Int = 0,
)

@Serializable
data class DeviceRegistrationError(
    val error: String,
    val message: String? = null,
    val revoked: Boolean = false,
)

@Serializable
data class AccountMeResponse(
    val user: AccountUserDto? = null,
)

@Serializable
data class AccountUserDto(
    val id: String? = null,
    val username: String? = null,
    val email: String? = null,
    @SerialName("isActive") val isActive: Boolean = true,
)
