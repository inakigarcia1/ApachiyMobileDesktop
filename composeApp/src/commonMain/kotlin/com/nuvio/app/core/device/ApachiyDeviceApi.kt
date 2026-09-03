package com.nuvio.app.core.device

import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.core.network.ApachiyConfig
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

object ApachiyDeviceApi {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun clientHeader(): String =
        "${AppVersionPolicy.clientHeaderPrefix}/${AppVersionPolicy.displayVersionName.ifBlank { "dev" }}"

    fun registerUrl(): String = apiUrl("v1/devices/register")

    fun listUrl(): String = apiUrl("v1/devices")

    fun accountMeUrl(): String = apiUrl("api/account/me")

    fun encodeRegistration(request: DeviceRegistrationRequest): String =
        json.encodeToString(request)

    fun decodeRegistration(body: String): DeviceRegistrationResponse =
        json.decodeFromString(body)

    fun decodeRegistrationError(body: String): DeviceRegistrationError? =
        runCatching { json.decodeFromString<DeviceRegistrationError>(body) }.getOrNull()

    fun decodeDeviceList(body: String): List<DeviceSummaryDto>? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val element = json.parseToJsonElement(trimmed)
            when (element) {
                is JsonArray -> json.decodeFromJsonElement<List<DeviceSummaryDto>>(element)
                else -> {
                    val root = element.jsonObject
                    val devices = root["devices"] ?: root["Devices"] ?: return@runCatching null
                    json.decodeFromJsonElement<List<DeviceSummaryDto>>(devices)
                }
            }
        }.getOrNull()
    }

    fun decodeDeviceListResponse(body: String): DeviceListResponseDto? =
        runCatching { json.decodeFromString<DeviceListResponseDto>(body) }.getOrNull()

    fun decodeAccountMe(body: String): AccountMeResponse =
        json.decodeFromString(body)

    suspend fun postRegister(bearerToken: String, request: DeviceRegistrationRequest) =
        httpRequestRaw(
            method = "POST",
            url = registerUrl(),
            headers = authHeaders(bearerToken, jsonBody = true),
            body = encodeRegistration(request),
        )

    suspend fun getDevices(bearerToken: String) =
        httpRequestRaw(
            method = "GET",
            url = listUrl(),
            headers = authHeaders(bearerToken),
            body = "",
        )

    suspend fun getAccountMe(bearerToken: String) =
        httpRequestRaw(
            method = "GET",
            url = accountMeUrl(),
            headers = authHeaders(bearerToken),
            body = "",
        )

    suspend fun deleteAccount(bearerToken: String) =
        httpRequestRaw(
            method = "DELETE",
            url = accountMeUrl(),
            headers = authHeaders(bearerToken),
            body = "",
        )

    private fun apiUrl(path: String): String {
        val base = ApachiyConfig.API_BASE_URL.trim().trimEnd('/')
        return "$base/${path.trimStart('/')}"
    }

    private fun authHeaders(bearerToken: String, jsonBody: Boolean = false): Map<String, String> = buildMap {
        put("Authorization", "Bearer $bearerToken")
        put("X-Apachiy-Client", clientHeader())
        put("Accept", "application/json")
        if (jsonBody) {
            put("Content-Type", "application/json")
        }
    }
}
