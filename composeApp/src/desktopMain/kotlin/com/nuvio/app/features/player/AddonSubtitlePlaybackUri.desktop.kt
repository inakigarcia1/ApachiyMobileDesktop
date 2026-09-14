package com.nuvio.app.features.player

import com.nuvio.app.core.network.rewriteLocalDevUrl
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.addons.DesktopAddonHttpClientProvider
import com.nuvio.app.features.addons.encodeUnsafeHttpUrlCharacters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.writeBytes

internal actual suspend fun prepareAddonSubtitlePlaybackUri(
    remoteUrl: String,
    sourceHeaders: Map<String, String>,
    cacheKey: String,
): String? = withContext(Dispatchers.IO) {
    val rewritten = rewriteLocalDevUrl(remoteUrl) ?: remoteUrl
    val resolvedUrl = rewritten
    val parts = parseRawHttpUrlParts(resolvedUrl)
    val httpUrl = resolvedUrl.toHttpUrlOrNull()
        ?: resolvedUrl.encodeUnsafeHttpUrlCharacters().toHttpUrlOrNull()
        ?: parts?.let { raw ->
            runCatching {
                okhttp3.HttpUrl.Builder()
                    .scheme(raw.scheme)
                    .host(raw.host)
                    .port(raw.port)
                    .encodedPath(raw.encodedPath)
                    .apply { raw.encodedQuery?.let { encodedQuery(it) } }
                    .build()
            }.getOrNull()
        }
    if (httpUrl == null) {
        return@withContext null
    }

    val requestBuilder = Request.Builder()
        .url(httpUrl)
        .header("Accept", "*/*")
    sourceHeaders.forEach { (key, value) ->
        if (key.isNotBlank() && value.isNotBlank() && !key.equals("Connection", ignoreCase = true)) {
            requestBuilder.header(key, value)
        }
    }

    val outcome = runCatching {
        DesktopAddonHttpClientProvider.get().newCall(requestBuilder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            Triple(response.code, response.header("Content-Type"), bytes)
        }
    }
    val result = outcome.getOrNull()
    if (result == null || result.first !in 200..299 || result.third.isEmpty()) {
        return@withContext null
    }

    val bodyText = result.third.toString(Charsets.UTF_8)
    if (bodyText.trimStart().startsWith("{") || bodyText.trimStart().startsWith("[")) {
        return@withContext null
    }

    val extension = sniffSubtitleFileExtension(bodyText, resolvedUrl)
    val cacheDir = DesktopStorage.cacheDir.resolve("addon_subtitles")
    Files.createDirectories(cacheDir)
    val cacheName = MessageDigest.getInstance("SHA-256")
        .digest(cacheKey.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
    val cacheFile = cacheDir.resolve("$cacheName.$extension")
    cacheFile.writeBytes(result.third)
    cacheFile.toUri().toString()
}
