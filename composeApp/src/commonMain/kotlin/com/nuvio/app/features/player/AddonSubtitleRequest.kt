package com.nuvio.app.features.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object AddonSubtitleRequest {
    fun isAttachSuccess(status: Int, body: String): Boolean {
        if (status !in 200..299) return false
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return true
        if (!trimmed.startsWith("{")) return false
        return runCatching {
            val element = Json.parseToJsonElement(trimmed)
            val ok = element.jsonObject["ok"]?.jsonPrimitive?.contentOrNull
            ok.equals("true", ignoreCase = true)
        }.getOrDefault(false)
    }
}
