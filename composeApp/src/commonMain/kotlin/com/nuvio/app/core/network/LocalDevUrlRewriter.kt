package com.nuvio.app.core.network

import com.nuvio.app.isDesktop

/**
 * Desktop local-dev builds talk to the same docker stack as the Android emulator,
 * so the API hands out URLs on the emulator host alias `10.0.2.2` and always
 * advertises its HTTPS origin. Those URLs are rewritten to the loopback origin
 * the desktop build already talks to, which avoids needing the ASP.NET dev cert.
 */
private val API_TLS_PORTS = setOf(8081, 10051)

internal fun rewriteEmulatorLoopbackUrl(
    url: String,
    apiOrigin: String? = localDevApiOrigin(),
): String {
    val hostSwapped = url.replace("10.0.2.2", "localhost")
    val loopback = parseLoopbackUrl(hostSwapped) ?: return hostSwapped

    if (loopback.port in API_TLS_PORTS && apiOrigin != null) {
        return apiOrigin + hostSwapped.substring(loopback.originLength)
    }

    // Remaining loopback services (metadata proxy, storage) are plain HTTP even
    // though the API advertises them as https://10.0.2.2. Never downgrade a port
    // that actually terminates TLS: that closes the connection mid-handshake.
    if (loopback.scheme == "https" && loopback.port != 443 && loopback.port !in API_TLS_PORTS) {
        return "http" + hostSwapped.substring("https".length)
    }
    return hostSwapped
}

private data class LoopbackUrl(val scheme: String, val port: Int, val originLength: Int)

private fun parseLoopbackUrl(url: String): LoopbackUrl? {
    val scheme = when {
        url.startsWith("http://", ignoreCase = true) -> "http"
        url.startsWith("https://", ignoreCase = true) -> "https"
        else -> return null
    }
    val hostStart = scheme.length + 3
    val afterScheme = url.substring(hostStart)
    val host = when {
        afterScheme.startsWith("localhost", ignoreCase = true) -> "localhost"
        afterScheme.startsWith("127.0.0.1") -> "127.0.0.1"
        else -> return null
    }
    val afterHost = afterScheme.substring(host.length)
    // Guard against hosts like "localhost.example.com".
    if (afterHost.isNotEmpty() && afterHost[0] !in charArrayOf(':', '/', '?', '#')) return null

    val portDigits = if (afterHost.startsWith(":")) afterHost.drop(1).takeWhile { it.isDigit() } else ""
    val port = when {
        portDigits.isNotEmpty() -> portDigits.toIntOrNull() ?: return null
        afterHost.startsWith(":") -> return null
        scheme == "https" -> 443
        else -> 80
    }
    val portLength = if (portDigits.isEmpty()) 0 else portDigits.length + 1
    return LoopbackUrl(
        scheme = scheme,
        port = port,
        originLength = hostStart + host.length + portLength,
    )
}

/** `scheme://host[:port]` of the locally configured API, or null when unusable. */
private fun localDevApiOrigin(): String? {
    val base = ApachiyConfig.API_BASE_URL.trim().takeIf { it.isNotEmpty() } ?: return null
    val schemeEnd = base.indexOf("://").takeIf { it > 0 } ?: return null
    val pathStart = base.indexOf('/', startIndex = schemeEnd + 3)
    val origin = if (pathStart == -1) base else base.substring(0, pathStart)
    return origin.takeIf { parseLoopbackUrl(it) != null }
}

internal fun rewriteLocalDevUrl(raw: String?): String? {
    val url = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!LocalDevConfig.ENABLED || !isDesktop) return url
    return rewriteEmulatorLoopbackUrl(url)
}
