package com.nuvio.app.features.autosync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Addon subtitle whose timing AutoSync or AudioSync last corrected. Cleared when that run stops. */
internal object AutoSyncSyncedSubtitle {
    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()

    fun mark(subtitleUrl: String) {
        _url.value = subtitleUrl
    }

    fun clear() {
        _url.value = null
    }
}
