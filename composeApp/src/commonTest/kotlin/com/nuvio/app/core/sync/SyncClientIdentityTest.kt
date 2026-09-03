package com.nuvio.app.core.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncClientIdentityTest {
    @Test
    fun generatedIdsAreUuidV4() {
        repeat(8) {
            val id = SyncClientIdentity.generateClientId()
            assertTrue(SyncClientIdentity.isValidUuidV4(id), id)
        }
    }

    @Test
    fun rejectsLegacyNuvioMobileIds() {
        assertFalse(SyncClientIdentity.isValidUuidV4("nuvio-mobile-0123456789abcdef0123456789abcdef"))
        assertFalse(SyncClientIdentity.isValidUuidV4("not-a-uuid"))
        assertTrue(SyncClientIdentity.isValidUuidV4("550e8400-e29b-41d4-a716-446655440000"))
    }
}
