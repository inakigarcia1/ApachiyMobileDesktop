package com.nuvio.app.features.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataTaskWithURL
import platform.Foundation.timeIntervalSince1970
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Serializable
private data class TorboxSpeedtestApiResponse(
    val success: Boolean = false,
    val data: List<TorboxSpeedtestEntry> = emptyList(),
)

@Serializable
private data class TorboxSpeedtestEntry(
    val url: String = "",
)

@Serializable
private data class StoredTorboxSpeed(
    val speedMbps: Double,
    val measuredAtEpochMs: Long,
)

internal actual object TorboxSpeedTestHarness {
    private val json = Json { ignoreUnknownKeys = true }
    private const val defaultsPrefix = "torbox_speed_"

    actual fun initializePlatform() = Unit

    actual fun readSample(): TorboxSpeedSample? {
        val defaults = NSUserDefaults.standardUserDefaults
        val speed = defaults.doubleForKey("${defaultsPrefix}speed")
        val at = defaults.doubleForKey("${defaultsPrefix}at").toLong()
        if (speed <= 0.0 || at <= 0L) return null
        return TorboxSpeedSample(speedMbps = speed, measuredAtEpochMs = at)
    }

    actual fun readStoredNetworkSignature(): String? = null

    actual fun currentNetworkSignature(): String? = null

    actual fun persist(sample: TorboxSpeedSample, networkSignature: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setDouble(sample.speedMbps, "${defaultsPrefix}speed")
        defaults.setDouble(sample.measuredAtEpochMs.toDouble(), "${defaultsPrefix}at")
    }

    actual suspend fun measure(): TorboxSpeedSample? = withContext(Dispatchers.Default) {
        val listBody = downloadString(TorboxSpeedTestPolicy.SPEEDTEST_LIST_URL) ?: return@withContext null
        val downloadUrl = runCatching {
            json.decodeFromString<TorboxSpeedtestApiResponse>(listBody)
                .data
                .firstOrNull { it.url.isNotBlank() }
                ?.url
        }.getOrNull() ?: return@withContext null

        val startMs = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
        var totalBytes = 0L
        val data = downloadDataForDuration(downloadUrl, TorboxSpeedTestPolicy.MEASURE_DURATION_MS) ?: return@withContext null
        totalBytes = data.length.toLong()
        val elapsed = (NSDate().timeIntervalSince1970 * 1000.0).toLong() - startMs
        val mbps = TorboxSpeedTestPolicy.calculateMbps(totalBytes, elapsed.coerceAtLeast(1L)) ?: return@withContext null
        TorboxSpeedSample(mbps, System.currentTimeMillis())
    }

    private suspend fun downloadString(url: String): String? = suspendCoroutine { cont ->
        val nsUrl = NSURL.URLWithString(url) ?: run {
            cont.resume(null)
            return@suspendCoroutine
        }
        NSURLSession.sharedSession.dataTaskWithURL(nsUrl) { data, _, error ->
            if (error != null || data == null) {
                cont.resume(null)
            } else {
                cont.resume((data as NSData).let { NSString.create(it, encoding = 4u)?.toString() })
            }
        }.resume()
    }

    private suspend fun downloadDataForDuration(url: String, maxMs: Long): NSData? {
        // iOS: single fetch then approximate; CDN file is large enough for short test window.
        return suspendCoroutine { cont ->
            val nsUrl = NSURL.URLWithString(url) ?: run {
                cont.resume(null)
                return@suspendCoroutine
            }
            NSURLSession.sharedSession.dataTaskWithURL(nsUrl) { data, _, error ->
                cont.resume(if (error == null) data as? NSData else null)
            }.resume()
        }
    }
}
