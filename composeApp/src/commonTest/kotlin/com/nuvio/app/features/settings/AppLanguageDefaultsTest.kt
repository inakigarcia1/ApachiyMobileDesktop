package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageDefaultsTest {
    @Test
    fun spanishCodeIsEs() {
        assertEquals("es", AppLanguage.SPANISH.code)
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromCode("es"))
    }
}
