package com.nuvio.app.features.player

import com.nuvio.app.core.network.rewriteLocalDevUrl
import com.nuvio.app.features.addons.AddonHttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal actual suspend fun prepareAddonSubtitlePlaybackUri(
    remoteUrl: String,
    sourceHeaders: Map<String, String>,
    cacheKey: String,
): String? = withContext(Dispatchers.IO) {
    val resolvedUrl = rewriteLocalDevUrl(remoteUrl) ?: remoteUrl
    val parts = parseRawHttpUrlParts(resolvedUrl)
    val parsedUrl = parts?.toHttpUrlPreservingEncodedPath() ?: return@withContext null
    val bodyText = downloadSubtitleBody(parsedUrl, sourceHeaders) ?: return@withContext null
    if (bodyText.trimStart().startsWith("{") || bodyText.trimStart().startsWith("[")) {
        return@withContext null
    }

    val extension = sniffSubtitleFileExtension(bodyText, resolvedUrl)
    val cacheDir = File(System.getProperty("java.io.tmpdir"), "nuvio_addon_subtitles").apply { mkdirs() }
    if (!cacheDir.isDirectory) return@withContext null
    val cacheName = MessageDigest.getInstance("SHA-256")
        .digest(cacheKey.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
    val cacheFile = File(cacheDir, "$cacheName.$extension")
    cacheFile.writeBytes(bodyText.toByteArray(Charsets.UTF_8))
    cacheFile.toURI().toString()
}

private fun RawHttpUrlParts.toHttpUrlPreservingEncodedPath(): HttpUrl? = runCatching {
    HttpUrl.Builder()
        .scheme(scheme)
        .host(host)
        .port(port)
        .encodedPath(encodedPath)
        .apply {
            encodedQuery?.let { encodedQuery(it) }
        }
        .build()
}.getOrNull()

private fun downloadSubtitleBody(
    url: HttpUrl,
    sourceHeaders: Map<String, String>,
): String? {
    val client = AddonHttpClientProvider.get().newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .retryOnConnectionFailure(true)
        .build()
    repeat(3) {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
        sourceHeaders.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank() && !key.equals("Connection", ignoreCase = true)) {
                requestBuilder.header(key, value)
            }
        }
        val outcome = runCatching {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                Triple(response.code, response.isSuccessful, bytes)
            }
        }
        val result = outcome.getOrNull()
        if (result != null && result.second && result.third.isNotEmpty()) {
            return result.third.toString(Charsets.UTF_8)
        }
    }
    return null
}
