package com.nuvio.app.core.network

import co.touchlab.kermit.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Dns
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Prefers IPv4 and falls back to DNS-over-HTTPS (Cloudflare/Google by IP) when the
 * system resolver hangs or fails.
 */
internal class DesktopIPv4FirstDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    private val log = Logger.withTag("DesktopIPv4FirstDns")

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isBlank()) throw UnknownHostException("empty hostname")
        if (isIpLiteral(hostname) || hostname.equals("localhost", ignoreCase = true)) {
            return listOf(InetAddress.getByName(hostname))
        }

        val systemAddresses = trySystemLookup(hostname)
        if (systemAddresses != null) {
            return preferIpv4(systemAddresses)
        }

        val dohAddresses = tryDohLookup(hostname)
        if (dohAddresses.isNotEmpty()) {
            return preferIpv4(dohAddresses)
        }

        throw UnknownHostException("Unable to resolve host \"$hostname\": system DNS and DoH failed")
    }

    private fun trySystemLookup(hostname: String): List<InetAddress>? {
        return try {
            DNS_EXECUTOR.submit<List<InetAddress>> {
                delegate.lookup(hostname)
            }.get(SYSTEM_DNS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (e: Exception) {
            log.w { "system DNS failed for $hostname: ${e.cause?.message ?: e.message}" }
            null
        }
    }

    private fun tryDohLookup(hostname: String): List<InetAddress> {
        for (endpoint in DOH_ENDPOINTS) {
            try {
                val ips = queryDohARecords(endpoint, hostname)
                if (ips.isNotEmpty()) {
                    return ips.map { InetAddress.getByName(it) }
                }
            } catch (e: Exception) {
                log.w { "DoH ${endpoint.name} failed for $hostname: ${e.message}" }
            }
        }
        return emptyList()
    }

    private fun queryDohARecords(endpoint: DohEndpoint, hostname: String): List<String> {
        val encoded = URLEncoder.encode(hostname, Charsets.UTF_8.name())
        val url = endpoint.urlTemplate.replace("{name}", encoded)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", endpoint.accept)
            connectTimeout = DOH_HTTP_TIMEOUT_MS
            readTimeout = DOH_HTTP_TIMEOUT_MS
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw UnknownHostException("DoH HTTP $code from ${endpoint.name}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseDohJsonARecords(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseDohJsonARecords(body: String): List<String> {
        val root = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<DohResponse>(body) }
            .getOrNull() ?: return emptyList()
        val status = root.status
        if (status != 0 && status != -1) {
            return emptyList()
        }
        return root.answer.orEmpty()
            .asSequence()
            .filter { it.type == 1 }
            .mapNotNull { it.data.trim().takeIf { data -> data.isNotEmpty() && isIpLiteral(data) } }
            .toList()
    }

    private fun preferIpv4(addresses: List<InetAddress>): List<InetAddress> {
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        return if (ipv4.isNotEmpty()) {
            ipv4
        } else {
            addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
        }
    }

    private fun isIpLiteral(value: String): Boolean {
        if (value.matches(IPV4_LITERAL)) return true
        return value.contains(':')
    }

    @Serializable
    private data class DohResponse(
        @SerialName("Status") val status: Int = 0,
        @SerialName("Answer") val answer: List<DohAnswer>? = null,
    )

    @Serializable
    private data class DohAnswer(
        val type: Int = 0,
        val data: String = "",
    )

    private data class DohEndpoint(
        val name: String,
        val urlTemplate: String,
        val accept: String,
    )

    companion object {
        private const val SYSTEM_DNS_TIMEOUT_SECONDS = 3L
        private const val DOH_HTTP_TIMEOUT_MS = 5000
        private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        private val DNS_EXECUTOR = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "desktop-ipv4-first-dns").apply { isDaemon = true }
        }
        private val DOH_ENDPOINTS = listOf(
            DohEndpoint(
                name = "cloudflare",
                urlTemplate = "https://1.1.1.1/dns-query?name={name}&type=A",
                accept = "application/dns-json",
            ),
            DohEndpoint(
                name = "google",
                urlTemplate = "https://8.8.8.8/resolve?name={name}&type=A",
                accept = "application/dns-json",
            ),
        )
    }
}
