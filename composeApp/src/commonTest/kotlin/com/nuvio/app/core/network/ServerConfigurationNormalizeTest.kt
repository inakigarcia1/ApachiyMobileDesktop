package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerConfigurationNormalizeTest {
    @Test
    fun upgradesHttpAndBareHostsToHttps() {
        assertEquals(
            "https://supabase.apachiy.org",
            normalizeOfficialBackendUrl("https://supabase.apachiy.org/"),
        )
        assertEquals(
            "https://supabase.apachiy.org",
            normalizeOfficialBackendUrl("http://supabase.apachiy.org"),
        )
        assertEquals(
            "https://supabase.apachiy.org",
            normalizeOfficialBackendUrl("supabase.apachiy.org"),
        )
        assertEquals("", normalizeOfficialBackendUrl("   "))
    }
}
