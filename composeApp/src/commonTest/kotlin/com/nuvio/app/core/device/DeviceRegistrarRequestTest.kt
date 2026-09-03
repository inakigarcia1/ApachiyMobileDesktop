package com.nuvio.app.core.device

import com.nuvio.app.core.auth.DeviceClientMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceRegistrarRequestTest {
    @Test
    fun androidRegistrationUsesApachiyClientFields() {
        val request = DeviceRegistrar.buildRequest(
            metadata = DeviceClientMetadata(
                deviceName = "Google Pixel 9",
                platform = "Android 16",
                osVersion = "16",
                apiPlatform = "android",
            ),
            installationId = "550e8400-e29b-41d4-a716-446655440000",
            appVersion = "1.2.3",
        )
        assertEquals("550e8400-e29b-41d4-a716-446655440000", request.installationId)
        assertEquals("android", request.platform)
        assertEquals("apachiy", request.app)
        assertEquals("1.2.3", request.appVersion)
        assertEquals("16", request.osVersion)
        assertEquals("Google Pixel 9", request.deviceModel)
    }

    @Test
    fun registrationJsonUsesCamelCasePropertyNames() {
        val encoded = ApachiyDeviceApi.encodeRegistration(
            DeviceRegistrar.buildRequest(
                metadata = DeviceClientMetadata(
                    deviceName = "Google Pixel 9",
                    platform = "Android 16",
                    osVersion = "16",
                    apiPlatform = "android",
                ),
                installationId = "550e8400-e29b-41d4-a716-446655440000",
                appVersion = "1.2.3",
            ),
        )
        assertTrue("\"installationId\"" in encoded)
        assertTrue("\"appVersion\"" in encoded)
        assertTrue("\"osVersion\"" in encoded)
        assertTrue("\"deviceModel\"" in encoded)
        assertFalse("installation_id" in encoded)
        assertFalse("app_version" in encoded)
    }
}
