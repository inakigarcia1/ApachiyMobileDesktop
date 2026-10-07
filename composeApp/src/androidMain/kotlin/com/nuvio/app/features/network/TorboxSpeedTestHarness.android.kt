package com.nuvio.app.features.network

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nuvio.app.features.addons.AddonHttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okio.IOException

private const val PREFS = "torbox_speed_test"
private const val KEY_SPEED = "speed_mbps"
private const val KEY_AT = "measured_at"
private const val KEY_CONN = "connection_type"
private const val KEY_NET_SIG = "network_signature"

@Serializable
private data class TorboxSpeedtestApiResponse(
    val success: Boolean = false,
    val data: List<TorboxSpeedtestEntry> = emptyList(),
)

@Serializable
private data class TorboxSpeedtestEntry(
    val url: String = "",
    val region: String? = null,
)

internal actual object TorboxSpeedTestHarness {
    private var appContext: Context? = null
    private val json = Json { ignoreUnknownKeys = true }

    actual fun initializePlatform() {
        // Context set from MainActivity via initialize(context)
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        AddonHttpClientProvider.initialize(context)
    }

    fun isDevelopmentBuild(): Boolean {
        val context = appContext ?: return false
        return context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    actual fun readPinnedMbps(): Double? = null

    actual fun setPinnedMbps(mbps: Double?) = Unit

    actual fun readSample(): TorboxSpeedSample? {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return null
        val speed = prefs.getFloat(KEY_SPEED, -1f).toDouble()
        val at = prefs.getLong(KEY_AT, 0L)
        if (speed <= 0.0 || at <= 0L) return null
        val conn = prefs.getString(KEY_CONN, null)?.takeIf { it.isNotBlank() }
        return TorboxSpeedSample(speedMbps = speed, measuredAtEpochMs = at, connectionType = conn)
    }

    actual fun readStoredNetworkSignature(): String? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_NET_SIG, null)
            ?.takeIf { it.isNotBlank() }

    actual fun persist(sample: TorboxSpeedSample, networkSignature: String?) {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putFloat(KEY_SPEED, sample.speedMbps.toFloat())
            .putLong(KEY_AT, sample.measuredAtEpochMs)
            .putString(KEY_CONN, sample.connectionType)
            .putString(KEY_NET_SIG, networkSignature)
            .apply()
    }

    actual fun currentNetworkSignature(): String? {
        val context = appContext ?: return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        return "$transport:$network"
    }

    actual suspend fun measure(): TorboxSpeedSample? = withContext(Dispatchers.IO) {
        val client = AddonHttpClientProvider.get()
        val listRequest = Request.Builder()
            .url(TorboxSpeedTestPolicy.SPEEDTEST_LIST_URL)
            .get()
            .build()
        val downloadUrl = try {
            client.newCall(listRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = json.decodeFromString<TorboxSpeedtestApiResponse>(body)
                parsed.data.firstOrNull { it.url.isNotBlank() }?.url
            }
        } catch (_: IOException) {
            null
        } ?: return@withContext null

        val downloadRequest = Request.Builder().url(downloadUrl).get().build()
        try {
            client.newCall(downloadRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                val source = body.source()
                val buffer = ByteArray(64 * 1024)
                val startMs = System.currentTimeMillis()
                var totalBytes = 0L
                while (System.currentTimeMillis() - startMs < TorboxSpeedTestPolicy.MEASURE_DURATION_MS) {
                    val read = source.read(buffer)
                    if (read == -1) break
                    totalBytes += read
                }
                val elapsed = System.currentTimeMillis() - startMs
                val mbps = TorboxSpeedTestPolicy.calculateMbps(totalBytes, elapsed) ?: return@withContext null
                val conn = currentNetworkSignature()?.substringBefore(':')
                TorboxSpeedSample(
                    speedMbps = mbps,
                    measuredAtEpochMs = System.currentTimeMillis(),
                    connectionType = conn,
                )
            }
        } catch (_: IOException) {
            null
        }
    }
}
