package com.nuvio.app.core.storage

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopStoragePersistenceTest {
    private var tempRoot: java.nio.file.Path? = null

    @AfterTest
    fun tearDown() {
        DesktopStorage.testRootOverride = null
        tempRoot?.let { Files.walk(it).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        tempRoot = null
    }

    @Test
    fun wipePreservesInstallationClientId() {
        val root = Files.createTempDirectory("apachiy-desktop-storage")
        tempRoot = root
        DesktopStorage.testRootOverride = root

        val installationId = "550e8400-e29b-41d4-a716-446655440000"
        DesktopStorage.store("apachiy_installation").putString("client_instance_id", installationId)
        DesktopStorage.store("nuvio_supabase_auth").putString("session", "secret")

        DesktopStorage.wipe()

        assertEquals(installationId, DesktopStorage.store("apachiy_installation").getString("client_instance_id"))
        assertNull(DesktopStorage.store("nuvio_supabase_auth").getString("session"))
    }

    @Test
    fun persistWritesNonEmptyPropertiesFile() {
        val root = Files.createTempDirectory("apachiy-desktop-storage")
        tempRoot = root
        val file = root.resolve("sample.properties")
        val store = DesktopStorage.Store(file)

        store.putString("key", "value")

        assertTrue(Files.size(file) > 0L)
        assertNotNull(store.getString("key"))
    }
}
