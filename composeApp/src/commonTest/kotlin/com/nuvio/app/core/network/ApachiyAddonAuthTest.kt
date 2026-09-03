package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApachiyAddonAuthTest {
    @Test
    fun attachesBearerOnlyOnApachiyApiHost() {
        val apiHost = ApachiyAddonAuth.hostFromBaseUrl("https://api.apachiy.org")
        assertTrue(ApachiyAddonAuth.shouldAttachAuth("api.apachiy.org", apiHost))
        assertTrue(ApachiyAddonAuth.shouldAttachAuth("API.apachiy.org", apiHost))
        assertFalse(ApachiyAddonAuth.shouldAttachAuth("v3-cinemeta.strem.io", apiHost))
        assertFalse(ApachiyAddonAuth.shouldAttachAuth("torrentio.strem.fun", apiHost))
        assertFalse(ApachiyAddonAuth.shouldAttachAuth("api.apachiy.org", ""))
        assertFalse(ApachiyAddonAuth.shouldAttachAuth("", apiHost))
    }

    @Test
    fun detectsAccountInactiveBody() {
        assertTrue(ApachiyAddonAuth.isAccountInactiveBody("""{"error":"account_inactive"}"""))
        assertFalse(ApachiyAddonAuth.isAccountInactiveBody("""{"error":"forbidden"}"""))
    }
}
