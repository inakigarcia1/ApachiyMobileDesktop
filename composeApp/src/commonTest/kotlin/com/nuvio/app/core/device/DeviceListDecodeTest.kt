package com.nuvio.app.core.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceListDecodeTest {
    @Test
    fun wrappedCamelCaseDeviceListDecodesInstallationIds() {
        val body = """
            {
              "devices": [
                {
                  "id": 40,
                  "installationId": "08da6e51-017c-4ce1-a026-2c8914ef2402",
                  "maxDevices": 1
                }
              ],
              "maxDevices": 1
            }
        """.trimIndent()

        val rows = ApachiyDeviceApi.decodeDeviceList(body)
        assertEquals(1, rows?.size)
        assertEquals(40L, rows?.first()?.id)
        assertEquals("08da6e51-017c-4ce1-a026-2c8914ef2402", rows?.first()?.resolvedInstallationId)
    }

    @Test
    fun wrappedEmptyDeviceListMeansTheSessionWasRemoved() {
        val rows = ApachiyDeviceApi.decodeDeviceList("""{"devices":[],"maxDevices":1}""")
        assertTrue(rows != null && rows.isEmpty())
    }

    @Test
    fun unknownObjectDoesNotLookLikeADeviceList() {
        assertNull(ApachiyDeviceApi.decodeDeviceList("""{"user":{"id":"abc"}}"""))
    }

    @Test
    fun rawArrayDeviceListStillDecodes() {
        val body = """[{"id":7,"installation_id":"abc-def"}]"""
        val rows = ApachiyDeviceApi.decodeDeviceList(body)
        assertEquals(1, rows?.size)
        assertEquals("abc-def", rows?.first()?.resolvedInstallationId)
    }
}
