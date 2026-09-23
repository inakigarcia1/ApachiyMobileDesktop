package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.device.ApachiyDeviceApi
import com.nuvio.app.core.network.ApachiyConfig
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import com.nuvio.app.core.sync.SyncClientIdentity
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AuthRepository")

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var initialized = false
    private var sessionStatusJob: Job? = null
    private var validatedRemoteUserId: String? = null

    var lastAuthKind: LastAuthKind = LastAuthKind.None
        private set

    fun initialize() {
        if (initialized) return
        initialized = true

        sessionStatusJob = scope.launch {
            SupabaseProvider.client.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val user = status.session.user
                        val userId = user?.id.orEmpty()
                        if (!validateRemoteSession(userId)) return@collect
                        _state.value = AuthState.Authenticated(
                            userId = userId,
                            email = user?.email,
                            isAnonymous = false,
                        )
                    }
                    is SessionStatus.NotAuthenticated -> {
                        // Supabase emits this during refresh gaps; keep persisted refresh tokens
                        // until explicit sign-out or a definitive invalid_grant from the server.
                        applyCachedSessionOrUnauthenticated()
                        if (hasPersistedSession()) {
                            scope.launch { refreshCurrentSession() }
                        }
                    }
                    is SessionStatus.Initializing -> {
                        _state.value = AuthState.Loading
                    }
                    is SessionStatus.RefreshFailure -> {
                        // Keep the cached session usable while Supabase retries.
                        // Forcing Unauthenticated here caused spurious login screens
                        // after overnight/token refresh blips even though refresh_token
                        // was still valid on disk.
                        applyCachedSessionOrUnauthenticated()
                        scope.launch {
                            refreshCurrentSession()
                        }
                    }
                }
            }
        }
    }

    private suspend fun validateRemoteSession(userId: String): Boolean {
        if (userId.isBlank() || validatedRemoteUserId == userId) return true

        return runCatching {
            SupabaseProvider.client.auth.retrieveUserForCurrentSession(false)
            validatedRemoteUserId = userId
            true
        }.getOrElse { e ->
            if (isLikelyExpiredAccessTokenError(e)) {
                val recovered = refreshCurrentSession()
                if (recovered) {
                    return runCatching {
                        SupabaseProvider.client.auth.retrieveUserForCurrentSession(false)
                        validatedRemoteUserId = userId
                        true
                    }.getOrElse { refreshError ->
                        if (isDefinitiveInvalidAccountError(refreshError)) {
                            log.w(refreshError) {
                                "Stored Supabase session no longer belongs to an active account; clearing local auth"
                            }
                            clearLocalSessionAfterRemoteInvalidation()
                            false
                        } else {
                            log.w(refreshError) {
                                "Unable to re-validate session after refresh; keeping cached auth state"
                            }
                            true
                        }
                    }
                }
                if (isDefinitiveInvalidAccountError(e)) {
                    log.w(e) { "Stored Supabase session no longer belongs to an active account; clearing local auth" }
                    clearLocalSessionAfterRemoteInvalidation()
                    false
                } else {
                    log.w(e) { "Access token expired and refresh failed transiently; keeping cached auth state" }
                    true
                }
            } else if (isDefinitiveInvalidAccountError(e)) {
                log.w(e) { "Stored Supabase session no longer belongs to an active account; clearing local auth" }
                clearLocalSessionAfterRemoteInvalidation()
                false
            } else {
                log.w(e) { "Unable to validate stored Supabase session; keeping cached auth state" }
                true
            }
        }
    }

    private fun applyCachedSessionOrUnauthenticated() {
        val session = SupabaseProvider.client.auth.currentSessionOrNull()
        val user = session?.user
        val userId = user?.id.orEmpty()
        if (user != null && userId.isNotBlank()) {
            _state.value = AuthState.Authenticated(
                userId = userId,
                email = user.email,
                isAnonymous = false,
            )
        } else if (_state.value !is AuthState.Authenticated) {
            _state.value = AuthState.Unauthenticated
        }
    }

    fun signInAnonymously() {
        _error.value = null
    }

    suspend fun refreshCurrentSession(): Boolean =
        try {
            SupabaseProvider.client.auth.refreshCurrentSession()
            applyCurrentSessionToState()
            true
        } catch (error: Throwable) {
            if (isDefinitiveInvalidAccountError(error)) {
                log.w(error) { "Refresh token rejected; clearing local auth" }
                clearLocalSessionAfterRemoteInvalidation()
            } else {
                log.w(error) { "Failed to refresh current session; keeping cached session" }
                applyCachedSessionOrUnauthenticated()
            }
            false
        }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> = runCatching {
        _error.value = null
        lastAuthKind = LastAuthKind.SignUp
        val sanitizedEmail = sanitizeAuthCredential(email)
        val sanitizedPassword = sanitizeAuthCredential(password, trim = false)
        SupabaseProvider.client.auth.signUpWith(Email) {
            this.email = sanitizedEmail
            this.password = sanitizedPassword
        }
        applyCurrentSessionToState()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        lastAuthKind = LastAuthKind.None
        log.e(e) { "Email sign-up failed" }
        _error.value = userFacingAuthError(e)
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        val first = signInWithEmailOnce(email, password, publishError = false)
        if (first.isSuccess) return first
        val failure = first.exceptionOrNull()
        if (failure == null || !isTransientHostLookupFailure(failure)) {
            failure?.let { _error.value = userFacingAuthError(it) }
            return first
        }
        delay(400)
        return signInWithEmailOnce(email, password, publishError = true)
    }

    private suspend fun signInWithEmailOnce(
        email: String,
        password: String,
        publishError: Boolean,
    ): Result<Unit> = runCatching {
        _error.value = null
        lastAuthKind = LastAuthKind.SignIn
        val sanitizedEmail = sanitizeAuthCredential(email)
        val sanitizedPassword = sanitizeAuthCredential(password, trim = false)
        SupabaseProvider.client.auth.signInWith(Email) {
            this.email = sanitizedEmail
            this.password = sanitizedPassword
        }
        applyCurrentSessionToState()
    }.onFailure { e ->
        if (e is CancellationException) throw e
        lastAuthKind = LastAuthKind.None
        log.e(e) { "Email sign-in failed" }
        if (publishError) {
            _error.value = userFacingAuthError(e)
        }
    }

    suspend fun signOut(): Result<Unit> {
        _error.value = null
        lastAuthKind = LastAuthKind.None
        val anonymousRead = runCatching { AuthStorage.loadAnonymousUserId() }
        val wasAnonymous = anonymousRead.getOrNull() != null
        val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
        validatedRemoteUserId = null
        SyncClientIdentity.clearRegisteredDeviceId()
        val remoteSignOut = if (wasAnonymous) {
            Result.success(Unit)
        } else {
            runCatching { SupabaseProvider.client.auth.signOut() }
        }

        val fallbackSessionClear = if (remoteSignOut.isFailure) {
            runCatching { SupabaseProvider.client.auth.clearSession() }
                .onFailure { error -> log.w(error) { "Failed to clear Supabase session after sign-out failure" } }
        } else {
            Result.success(Unit)
        }
        val localCleanup = runCatching { LocalAccountDataCleaner.wipe() }
        _state.value = AuthState.Unauthenticated

        val failure = anonymousRead.exceptionOrNull()
            ?: anonymousClear.exceptionOrNull()
            ?: remoteSignOut.exceptionOrNull()
            ?: fallbackSessionClear.exceptionOrNull()
            ?: localCleanup.exceptionOrNull()
        val cancellation = remoteSignOut.exceptionOrNull() as? CancellationException
            ?: fallbackSessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) {
            Result.success(Unit)
        } else {
            log.e(failure) { "Sign-out did not complete cleanly; all local cleanup steps were attempted" }
            _error.value = failure.message ?: runCatching {
                getString(Res.string.auth_sign_out_failed)
            }.getOrDefault("Sign out failed")
            Result.failure(failure)
        }
    }

    suspend fun prepareForServerSwitch(): Result<Unit> {
        _error.value = null
        val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
        validatedRemoteUserId = null
        SyncClientIdentity.clearRegisteredDeviceId()
        val sessionClear = runCatching { SupabaseProvider.client.auth.clearSession() }
        _state.value = AuthState.Unauthenticated
        val failure = anonymousClear.exceptionOrNull() ?: sessionClear.exceptionOrNull()
        val cancellation = sessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) Result.success(Unit) else Result.failure(failure)
    }

    fun reinitialize() {
        sessionStatusJob?.cancel()
        sessionStatusJob = null
        initialized = false
        validatedRemoteUserId = null
        _state.value = AuthState.Loading
        initialize()
    }

    suspend fun signOutIfSessionInvalid(error: Throwable, source: String): Boolean {
        if (isLikelyExpiredAccessTokenError(error) && !isDefinitiveInvalidAccountError(error)) {
            val recovered = refreshCurrentSession()
            if (recovered) return false
            // Transient refresh failure: keep the local session.
            return false
        }
        if (!isDefinitiveInvalidAccountError(error)) return false

        log.w(error) { "$source failed because the current Supabase account/session is no longer valid; clearing local auth" }
        clearLocalSessionAfterRemoteInvalidation()
        return true
    }

    private suspend fun clearLocalSessionAfterRemoteInvalidation() {
        _error.value = null
        AuthStorage.clearAnonymousUserId()
        validatedRemoteUserId = null
        SyncClientIdentity.clearRegisteredDeviceId()
        runCatching {
            SupabaseProvider.client.auth.clearSession()
        }.onFailure { e ->
            log.w(e) { "Failed to clear Supabase session after remote invalidation; continuing local reset" }
        }
        val localCleanup = runCatching { LocalAccountDataCleaner.wipe() }
        _state.value = AuthState.Unauthenticated
        localCleanup.onFailure { error ->
            log.e(error) { "Local account cleanup failed after remote session invalidation" }
        }
    }

    suspend fun deleteAccount(): Result<Unit> = runCatching {
        _error.value = null
        if (ApachiyConfig.API_BASE_URL.isBlank()) {
            error("API base URL is not configured.")
        }
        val token = SupabaseProvider.client.auth.currentAccessTokenOrNull()
            ?: error("Not authenticated.")
        val response = ApachiyDeviceApi.deleteAccount(token)
        if (response.status !in 200..299) {
            error("Account deletion failed with status ${response.status}.")
        }
        SupabaseProvider.client.auth.signOut()
        validatedRemoteUserId = null
        try {
            LocalAccountDataCleaner.wipe()
        } finally {
            _state.value = AuthState.Unauthenticated
        }
    }.onFailure { e ->
        log.e(e) { "Account deletion failed" }
        _error.value = e.message ?: getString(Res.string.auth_account_deletion_failed)
    }

    fun clearError() {
        _error.value = null
    }

    fun hasPersistedSession(): Boolean =
        SupabaseProvider.client.auth.currentSessionOrNull()?.let { session ->
            session.refreshToken.isNotBlank() || session.accessToken.isNotBlank()
        } == true

    private fun applyCurrentSessionToState() {
        val session = SupabaseProvider.client.auth.currentSessionOrNull() ?: return
        val user = session.user ?: return
        val userId = user.id
        if (userId.isBlank()) return
        _state.value = AuthState.Authenticated(
            userId = userId,
            email = user.email,
            isAnonymous = false,
        )
    }

    fun setError(message: String) {
        _error.value = message
    }

    private fun Throwable.safeAuthErrorDescription(): String? =
        findCause<AuthRestException>()
            ?.errorDescription
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: findCause<RestException>()
                ?.description
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

    private suspend fun userFacingAuthError(error: Throwable): String =
        getString(
            authErrorStringResource(
                error,
                deviceOfflineLike = NetworkStatusRepository.uiState.value.isOfflineLike,
            ),
        )
}

private fun isTransientHostLookupFailure(error: Throwable): Boolean {
    val message = buildString {
        append(error.message.orEmpty())
        var cause = error.cause
        while (cause != null) {
            append(' ')
            append(cause.message.orEmpty())
            cause = cause.cause
        }
    }.lowercase()
    return message.contains("unable to resolve host") ||
        message.contains("no address associated") ||
        message.contains("unknownhost") ||
        message.contains("failed to lookup")
}

internal fun sanitizeAuthCredential(value: String, trim: Boolean = true): String {
    val withoutNuls = buildString(value.length) {
        value.forEach { ch ->
            if (ch != '\u0000') append(ch)
        }
    }
    return if (trim) withoutNuls.trim() else withoutNuls
}
