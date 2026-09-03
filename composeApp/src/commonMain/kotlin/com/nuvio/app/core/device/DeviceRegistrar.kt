package com.nuvio.app.core.device

import co.touchlab.kermit.Logger
import com.nuvio.app.core.account.AccountStatusRepository
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.DeviceLimitNotifier
import com.nuvio.app.core.auth.LastAuthKind
import com.nuvio.app.core.auth.currentDeviceClientMetadata
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.network.ApachiyConfig
import com.nuvio.app.core.sync.SyncClientIdentity
import io.github.jan.supabase.auth.auth
import com.nuvio.app.core.network.SupabaseProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val MAX_ATTEMPTS = 3
private const val RETRY_BASE_DELAY_MS = 5_000L
private const val MIN_RETRY_GAP_MS = 5_000L
private const val MAX_DEVICE_NAME_LENGTH = 160

object DeviceRegistrar {
    private val log = Logger.withTag("ApachiyDeviceRegistrar")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var lastAttemptMark: TimeMark? = null
    private var observingStarted = false

    fun startObserving() {
        if (observingStarted) return
        observingStarted = true
        scope.launch {
            AuthRepository.state
                .map { state -> state is AuthState.Authenticated && !state.isAnonymous }
                .distinctUntilChanged()
                .collect { isAuthed ->
                    if (isAuthed) {
                        registerNow()
                    } else {
                        SyncClientIdentity.clearRegisteredDeviceId()
                    }
                }
        }
    }

    fun requestForegroundRegistration() {
        scope.launch { registerNow() }
    }

    private suspend fun registerNow() {
        if (ApachiyConfig.API_BASE_URL.isBlank()) {
            log.d { "APACHIY_API_BASE_URL empty; skipping device registration." }
            return
        }
        val state = AuthRepository.state.value
        if (state !is AuthState.Authenticated || state.isAnonymous) return
        val accessToken = runCatching {
            SupabaseProvider.client.auth.currentAccessTokenOrNull()
        }.getOrNull() ?: run {
            log.w { "Authenticated but no access token; will retry on next state change." }
            return
        }
        mutex.withLock {
            val last = lastAttemptMark
            if (last != null && last.elapsedNow().inWholeMilliseconds < MIN_RETRY_GAP_MS) {
                return
            }
            lastAttemptMark = TimeSource.Monotonic.markNow()
            val request = buildRequest()
            log.i { "registering device installation_id=${request.installationId.take(8)}…" }
            try {
                attemptWithRetry(accessToken, request)
            } finally {
                AccountStatusRepository.refresh()
            }
        }
    }

    private suspend fun attemptWithRetry(accessToken: String, request: DeviceRegistrationRequest) {
        var attempt = 0
        var lastError: Throwable? = null
        var currentToken = accessToken
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            try {
                val response = ApachiyDeviceApi.postRegister(currentToken, request)
                if (response.status in 200..299) {
                    val body = runCatching { ApachiyDeviceApi.decodeRegistration(response.body) }.getOrNull()
                    val deviceId = body?.deviceId?.takeIf { it > 0L }
                    log.i { "device registered id=$deviceId created=${body?.created} revoked=${body?.revoked}" }
                    if (deviceId != null) {
                        SyncClientIdentity.saveRegisteredDeviceId(deviceId)
                    }
                    if (body?.revoked == true) {
                        log.w { "device was reported revoked; signing out" }
                        AuthRepository.signOut()
                    }
                    return
                }
                val err = ApachiyDeviceApi.decodeRegistrationError(response.body)
                when (response.status) {
                    401, 403 -> {
                        val refreshed = AuthRepository.refreshCurrentSession()
                        val newToken = runCatching {
                            SupabaseProvider.client.auth.currentAccessTokenOrNull()
                        }.getOrNull()
                        if (refreshed && newToken != null && newToken != currentToken) {
                            currentToken = newToken
                            continue
                        }
                        log.w { "device registration rejected (${response.status}): ${response.body}" }
                        return
                    }
                    410, 423 -> {
                        log.w { "device revoked on server; signing out" }
                        AuthRepository.signOut()
                        return
                    }
                    409 -> {
                        if (err?.error == "max_devices_exceeded") {
                            handleMaxDevicesExceeded()
                        } else {
                            log.w { "device registration conflict (409): ${response.body}" }
                        }
                        return
                    }
                    in 500..599 -> {
                        log.w { "server error ${response.status}; will retry (attempt $attempt/$MAX_ATTEMPTS)" }
                        lastError = RuntimeException("HTTP ${response.status}")
                    }
                    else -> {
                        log.w { "device registration failed (${response.status}): ${response.body}" }
                        return
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error) { "device registration error (attempt $attempt/$MAX_ATTEMPTS)" }
                lastError = error
            }
            delay(RETRY_BASE_DELAY_MS * (1L shl (attempt - 1)))
        }
        log.e { "device registration gave up after $MAX_ATTEMPTS attempts: ${lastError?.message}" }
    }

    private suspend fun handleMaxDevicesExceeded() {
        log.w { "max devices exceeded for user" }
        DeviceLimitNotifier.notifyMaxDevicesExceeded()
        if (AuthRepository.lastAuthKind != LastAuthKind.SignUp) {
            AuthRepository.signOut()
        }
    }

    internal fun buildRequest(
        metadata: com.nuvio.app.core.auth.DeviceClientMetadata = currentDeviceClientMetadata(),
        installationId: String = SyncClientIdentity.currentClientId(),
        appVersion: String = AppVersionConfig.VERSION_NAME.ifBlank { "dev" },
    ): DeviceRegistrationRequest {
        val deviceModel = metadata.deviceName.take(MAX_DEVICE_NAME_LENGTH)
        return DeviceRegistrationRequest(
            installationId = installationId,
            platform = metadata.apiPlatform,
            app = "apachiy",
            appVersion = appVersion,
            osVersion = metadata.osVersion,
            deviceModel = deviceModel,
        )
    }
}
