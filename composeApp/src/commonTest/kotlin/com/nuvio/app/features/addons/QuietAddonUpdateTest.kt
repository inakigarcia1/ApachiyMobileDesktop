package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuietAddonUpdateTest {
    private val current = listOf(
        QuietAddonRow(url = "https://a/manifest.json", enabled = true, name = "A", manifestToken = "v1"),
    )

    @Test
    fun emptyOrFailedRemoteDoesNotReplaceLocal() {
        assertNull(resolveQuietAddonRows(current, remote = emptyList(), manifests = emptyMap()))
        assertNull(resolveQuietAddonRows(current, remote = null, manifests = emptyMap()))
    }

    @Test
    fun identicalManifestDoesNotPublish() {
        val remote = listOf(QuietRemoteAddon("https://a/manifest.json", enabled = true, name = "A"))
        val manifests = mapOf("https://a/manifest.json" to QuietManifestResult.Ready("v1"))
        assertNull(resolveQuietAddonRows(current, remote, manifests))
    }

    @Test
    fun failedManifestKeepsThePreviousOne() {
        val remote = listOf(QuietRemoteAddon("https://a/manifest.json", enabled = false, name = "A"))
        val manifests = mapOf("https://a/manifest.json" to QuietManifestResult.Failed)
        assertEquals(
            listOf(current.single().copy(enabled = false)),
            resolveQuietAddonRows(current, remote, manifests),
        )
    }

    @Test
    fun nonEmptyRemoteReplacesUrlsAndDropsAFailedNewUrl() {
        val remote = listOf(
            QuietRemoteAddon("https://b/manifest.json", enabled = true, name = "B"),
            QuietRemoteAddon("https://c/manifest.json", enabled = true, name = null),
        )
        val manifests = mapOf(
            "https://b/manifest.json" to QuietManifestResult.Ready("v2"),
            "https://c/manifest.json" to QuietManifestResult.Failed,
        )
        assertEquals(
            listOf(QuietAddonRow("https://b/manifest.json", enabled = true, name = "B", manifestToken = "v2")),
            resolveQuietAddonRows(current, remote, manifests),
        )
    }
}
