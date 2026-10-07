package com.nuvio.app.features.network

import com.nuvio.app.features.addons.DesktopAddonHttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okio.IOException
import java.io.File
import java.nio.charset.StandardCharsets

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
    val networkSignature: String? = null,
    val pinnedMbps: Double? = null,
)

internal actual object TorboxSpeedTestHarness {
    private val json = Json { ignoreUnknownKeys = true }

    private fun storageFile(): File {
        val base = File(System.getProperty("user.home"), ".apachiy")
        base.mkdirs()
        return File(base, "torbox-speed.json")
    }

    actual fun initializePlatform() = Unit

    actual fun readPinnedMbps(): Double? = readStored()?.pinnedMbps?.takeIf { it > 0.0 }

    actual fun setPinnedMbps(mbps: Double?) {
        val stored = readStored() ?: StoredTorboxSpeed(0.0, 0L)
        val next = if (mbps == null || mbps <= 0.0) {
            stored.copy(pinnedMbps = null)
        } else {
            stored.copy(pinnedMbps = mbps)
        }
        writeStored(next)
    }

    actual fun readSample(): TorboxSpeedSample? {
        val stored = readStored() ?: return null
        val pinned = stored.pinnedMbps?.takeIf { it > 0.0 }
        if (pinned != null) {
            return TorboxSpeedSample(
                speedMbps = pinned,
                measuredAtEpochMs = System.currentTimeMillis(),
                connectionType = "pinned",
            )
        }
        if (stored.speedMbps <= 0.0 || stored.measuredAtEpochMs <= 0L) return null
        return TorboxSpeedSample(stored.speedMbps, stored.measuredAtEpochMs, connectionType = null)
    }

    private fun readStored(): StoredTorboxSpeed? {
        val file = storageFile()
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<StoredTorboxSpeed>(file.readText(StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private fun writeStored(payload: StoredTorboxSpeed) {
        storageFile().writeText(
            json.encodeToString(StoredTorboxSpeed.serializer(), payload),
            StandardCharsets.UTF_8,
        )
    }

    actual fun readStoredNetworkSignature(): String? = null

    actual fun currentNetworkSignature(): String? = null

    actual fun persist(sample: TorboxSpeedSample, networkSignature: String?) {
        val previous = readStored()
        val payload = StoredTorboxSpeed(
            speedMbps = sample.speedMbps,
            measuredAtEpochMs = sample.measuredAtEpochMs,
            networkSignature = networkSignature,
            pinnedMbps = previous?.pinnedMbps,
        )
        writeStored(payload)
    }

    actual suspend fun measure(): TorboxSpeedSample? = withContext(Dispatchers.IO) {
        val client = DesktopAddonHttpClientProvider.get()
        val listRequest = Request.Builder()
            .url(TorboxSpeedTestPolicy.SPEEDTEST_LIST_URL)
            .get()
            .build()
        val downloadUrl = try {
            client.newCall(listRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                json.decodeFromString<TorboxSpeedtestApiResponse>(body)
                    .data
                    .firstOrNull { it.url.isNotBlank() }
                    ?.url
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
                TorboxSpeedSample(mbps, System.currentTimeMillis())
            }
        } catch (_: IOException) {
            null
        }
    }
}
