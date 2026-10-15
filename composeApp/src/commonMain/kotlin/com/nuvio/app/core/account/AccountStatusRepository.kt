package com.nuvio.app.core.account

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.device.ApachiyDeviceApi
import com.nuvio.app.core.network.ApachiyConfig
import io.github.jan.supabase.auth.auth
import com.nuvio.app.core.network.SupabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object AccountStatusRepository {
    private val log = Logger.withTag("AccountStatusRepository")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isActive = MutableStateFlow<Boolean?>(null)
    val isActive: StateFlow<Boolean?> = _isActive.asStateFlow()
    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private var observingStarted = false

    fun startObserving() {
        if (observingStarted) return
        observingStarted = true
        scope.launch {
            AuthRepository.state
                .map { state -> state is AuthState.Authenticated && !state.isAnonymous }
                .distinctUntilChanged()
                .collect { isAuthed ->
                    if (!isAuthed) {
                        clear()
                    }
                }
        }
    }

    suspend fun refresh(): Boolean? {
        if (ApachiyConfig.API_BASE_URL.isBlank()) return null
        val token = runCatching {
            SupabaseProvider.client.auth.currentAccessTokenOrNull()
        }.getOrNull()
        if (token.isNullOrBlank()) return null

        return runCatching {
            val response = ApachiyDeviceApi.getAccountMe(token)
            if (response.status !in 200..299) {
                log.w { "GET /api/account/me failed: ${response.status}" }
                _isAdmin.value = false
                return null
            }
            val user = ApachiyDeviceApi.decodeAccountMe(response.body).user ?: run {
                _isAdmin.value = false
                return null
            }
            val active = user.isActive
            _isActive.value = active
            _isAdmin.value = user.isAdmin
            if (!active) {
                InactiveSubscriptionNotifier.notifyInactiveSubscription()
            }
            active
        }.onFailure { error ->
            _isAdmin.value = false
            log.w(error) { "Failed to refresh account status" }
        }.getOrNull()
    }

    fun markInactive() {
        _isActive.value = false
        InactiveSubscriptionNotifier.notifyInactiveSubscription()
    }

    fun clear() {
        _isActive.value = null
        _isAdmin.value = false
    }

    fun canStartPlayback(): Boolean = _isActive.value != false
}
