package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeniedAddonUrlsTest {
    @Test
    fun blocksCinemetaAndOpensubtitlesHosts() {
        assertTrue(DeniedAddonUrls.isDeniedAddonUrl("https://v3-cinemeta.strem.io/manifest.json"))
        assertTrue(DeniedAddonUrls.isDeniedAddonUrl("https://cinemeta.strem.io/manifest.json"))
        assertTrue(DeniedAddonUrls.isDeniedAddonUrl("https://opensubtitles-v3.strem.io/manifest.json"))
        assertTrue(DeniedAddonUrls.isDeniedAddonUrl("https://opensubtitles.strem.io/manifest.json"))
        assertTrue(DeniedAddonUrls.isDeniedAddonUrl("stremio://cinemeta.strem.io/manifest.json"))
    }

    @Test
    fun allowsOtherAddonHosts() {
        assertFalse(DeniedAddonUrls.isDeniedAddonUrl("https://torrentio.strem.fun/manifest.json"))
        assertFalse(DeniedAddonUrls.isDeniedAddonUrl("https://api.apachiy.org/addons/catalog/manifest.json"))
    }
}
