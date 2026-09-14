package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerConfigurationNormalizeTest {
    @Test
    fun upgradesPublicHttpAndBareHostsToHttps() {
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

    @Test
    fun preservesHttpForLocalAndPrivateHosts() {
        assertEquals(
            "http://localhost:8000",
            normalizeOfficialBackendUrl("http://localhost:8000"),
        )
        assertEquals(
            "http://10.0.2.2:8000",
            normalizeOfficialBackendUrl("http://10.0.2.2:8000"),
        )
        assertEquals(
            "http://192.168.100.71:8000",
            normalizeOfficialBackendUrl("http://192.168.100.71:8000"),
        )
    }
}
