package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolRelativeImageUrlMapperTest {
    @Test
    fun `protocol-relative urls become https`() {
        assertEquals(
            "https://images.metahub.space/poster.jpg",
            normalizeLoadableImageUrl("//images.metahub.space/poster.jpg"),
        )
    }

    @Test
    fun `absolute urls are left for coil`() {
        assertNull(normalizeLoadableImageUrl("https://images.metahub.space/poster.jpg"))
        assertNull(normalizeLoadableImageUrl("http://cdn.example/poster.jpg"))
    }
}
