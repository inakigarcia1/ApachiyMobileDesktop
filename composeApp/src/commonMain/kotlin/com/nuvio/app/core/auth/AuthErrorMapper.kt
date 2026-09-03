package com.nuvio.app.core.auth

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.account_error_connection_refused
import nuvio.composeapp.generated.resources.account_error_connection_timeout
import nuvio.composeapp.generated.resources.account_error_email_already_registered
import nuvio.composeapp.generated.resources.account_error_email_not_confirmed
import nuvio.composeapp.generated.resources.account_error_invalid_credentials
import nuvio.composeapp.generated.resources.account_error_invalid_email
import nuvio.composeapp.generated.resources.account_error_invalid_request
import nuvio.composeapp.generated.resources.account_error_no_internet
import nuvio.composeapp.generated.resources.account_error_not_authenticated
import nuvio.composeapp.generated.resources.account_error_password_too_short
import nuvio.composeapp.generated.resources.account_error_password_too_weak
import nuvio.composeapp.generated.resources.account_error_rate_limited
import nuvio.composeapp.generated.resources.account_error_service_unavailable
import nuvio.composeapp.generated.resources.account_error_signup_disabled
import nuvio.composeapp.generated.resources.account_error_unexpected
import org.jetbrains.compose.resources.StringResource

fun authErrorStringResource(error: Throwable): StringResource {
    val message = buildString {
        append(error.message.orEmpty())
        var cause = error.cause
        while (cause != null) {
            append(' ')
            append(cause.message.orEmpty())
            cause = cause.cause
        }
    }.lowercase()

    return when {
        message.contains("invalid login credentials") -> Res.string.account_error_invalid_credentials
        message.contains("email not confirmed") -> Res.string.account_error_email_not_confirmed
        message.contains("user already registered") -> Res.string.account_error_email_already_registered
        message.contains("invalid email") -> Res.string.account_error_invalid_email
        message.contains("password") && message.contains("short") -> Res.string.account_error_password_too_short
        message.contains("password") && message.contains("weak") -> Res.string.account_error_password_too_weak
        message.contains("signup is disabled") -> Res.string.account_error_signup_disabled
        message.contains("rate limit") || message.contains("too many requests") ->
            Res.string.account_error_rate_limited
        message.contains("unable to resolve host") ||
            message.contains("no address associated") ||
            message.contains("unknownhost") ||
            message.contains("failed to lookup") ->
            Res.string.account_error_no_internet
        message.contains("timeout") || message.contains("timed out") -> Res.string.account_error_connection_timeout
        message.contains("connection refused") ||
            message.contains("connect failed") ||
            message.contains("failed to connect") ||
            message.contains("cleartext") ->
            Res.string.account_error_connection_refused
        message.contains("not authenticated") -> Res.string.account_error_not_authenticated
        message.contains("404") || message.contains("could not find") -> Res.string.account_error_service_unavailable
        message.contains("400") || message.contains("bad request") -> Res.string.account_error_invalid_request
        else -> Res.string.account_error_unexpected
    }
}
