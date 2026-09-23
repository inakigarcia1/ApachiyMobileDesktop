package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.account_error_invalid_credentials
import nuvio.composeapp.generated.resources.account_error_service_unavailable
import kotlin.test.assertEquals

class AuthPolicyTest {
    @Test
    fun anonymousSessionsAreNotLoggedIn() {
        val anonymous = AuthState.Authenticated(userId = "user", email = null, isAnonymous = true)
        val account = AuthState.Authenticated(userId = "user", email = "a@b.c", isAnonymous = false)
        assertFalse(anonymous.isLoggedIn)
        assertTrue(account.isLoggedIn)
        assertFalse(AuthState.Unauthenticated.isLoggedIn)
    }

    @Test
    fun mapsGoTrueInvalidCredentials() {
        assertEquals(
            Res.string.account_error_invalid_credentials,
            authErrorStringResource(RuntimeException("Invalid login credentials")),
        )
    }

    @Test
    fun stripsNullBytesFromAuthCredentials() {
        val dirtyEmail = "user\u0000@apachiy.org\u0000"
        val dirtyPassword = "secret\u0000pass"
        assertEquals("user@apachiy.org", sanitizeAuthCredential(dirtyEmail))
        assertEquals("secretpass", sanitizeAuthCredential(dirtyPassword, trim = false))
    }

    @Test
    fun mapsSchemaQueryFailureToServiceUnavailable() {
        assertEquals(
            Res.string.account_error_service_unavailable,
            authErrorStringResource(
                RuntimeException("500: Database error querying schema unexpected_failure"),
            ),
        )
    }

    @Test
    fun expiredAccessTokenIsRecoverableAndNotADefinitiveAccountLoss() {
        val expired = RuntimeException("JWT expired")
        assertTrue(isLikelyExpiredAccessTokenError(expired))
        assertFalse(isDefinitiveInvalidAccountError(expired))
    }

    @Test
    fun deletedUserIsADefinitiveAccountLoss() {
        val deleted = RuntimeException("User from sub claim in JWT does not exist")
        assertTrue(isDefinitiveInvalidAccountError(deleted))
    }
}
