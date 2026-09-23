package com.nuvio.app.core.network

import io.github.jan.supabase.auth.SessionManager

/**
 * Optional platform-specific Supabase session manager.
 * Returning null keeps the library default (multiplatform-settings Preferences).
 */
internal expect fun createPlatformAuthSessionManager(): SessionManager?
