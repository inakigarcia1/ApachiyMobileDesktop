package com.nuvio.app.core.diagnostics

internal actual fun reportUnexpectedSignOut(reason: String) {
    SentryInitializer.reportUnexpectedSignOut(reason)
}
