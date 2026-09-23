package com.nuvio.app.core.auth

import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.RestException

internal fun isLikelyExpiredAccessTokenError(error: Throwable): Boolean {
    val restError = error.findCause<RestException>()
    val message = authErrorMessage(error, restError)

    if ("token expired" in message || "jwt expired" in message) return true
    if ("jwt" in message && "expired" in message) return true

    // Expired access tokens commonly surface as 401/403 without a deleted-user signal.
    return (restError?.statusCode == 401 || restError?.statusCode == 403) &&
        !isDefinitiveInvalidAccountError(error)
}

internal fun isDefinitiveInvalidAccountError(error: Throwable): Boolean {
    val restError = error.findCause<RestException>()
    val message = authErrorMessage(error, restError)

    return (
        "user" in message &&
            ("does not exist" in message || "not found" in message || "deleted" in message)
        ) || (
        "foreign key" in message &&
            ("auth.users" in message || "user_id" in message)
        ) || (
        "refresh_token" in message &&
            ("not found" in message || "invalid" in message || "revoked" in message)
        ) || (
        "session" in message &&
            ("not found" in message || "missing" in message)
        )
}

internal fun isInvalidRemoteSessionError(error: Throwable): Boolean {
    val restError = error.findCause<RestException>()
    if (restError?.statusCode == 401 || restError?.statusCode == 403) return true

    val message = authErrorMessage(error, restError)
    return (
        "jwt" in message &&
            ("invalid" in message || "expired" in message || "malformed" in message)
        ) || isDefinitiveInvalidAccountError(error)
}

private fun authErrorMessage(error: Throwable, restError: RestException?): String {
    return buildString {
        append(error.message.orEmpty())
        append(' ')
        append(error.findCause<AuthRestException>()?.errorDescription.orEmpty())
        if (restError != null) {
            append(' ')
            append(restError.error)
            append(' ')
            append(restError.description)
        }
    }.lowercase()
}

internal inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}
