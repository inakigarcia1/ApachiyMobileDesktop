package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.account_error_invalid_credentials
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
}
