package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonSubtitleAttachResponseTest {
    @Test
    fun isAttachSuccessAcceptsOkJsonOrEmptyBody() {
        assertTrue(AddonSubtitleRequest.isAttachSuccess(200, """{"ok":true,"subtitles":[]}"""))
        assertTrue(AddonSubtitleRequest.isAttachSuccess(204, ""))
        assertFalse(AddonSubtitleRequest.isAttachSuccess(405, """{"ok":true}"""))
        assertFalse(AddonSubtitleRequest.isAttachSuccess(200, """{"subtitles":[]}"""))
    }
}
