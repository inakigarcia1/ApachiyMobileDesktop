package com.nuvio.app.features.player.audiosync

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Same GigaSpeech Zipformer int8 model as Android, stored under the user home directory. */
internal object DesktopAsrModel {
    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"

    private const val VERSION = "gigaspeech-2023-12-12"
    private const val MIRROR = "https://github.com/DavidVamaiotu/NuvioMobile-AutoSync/releases/download/asr-models/"
    private const val UPSTREAM = "https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-gigaspeech-2023-12-12/resolve/main/"

    private val files = listOf(
        Triple(ENCODER, "gigaspeech-encoder.int8.onnx", "encoder-epoch-30-avg-1.int8.onnx") to 72_850_738L,
        Triple(DECODER, "gigaspeech-decoder.int8.onnx", "decoder-epoch-30-avg-1.int8.onnx") to 540_688L,
        Triple(JOINER, "gigaspeech-joiner.int8.onnx", "joiner-epoch-30-avg-1.int8.onnx") to 259_417L,
        Triple(TOKENS, "gigaspeech-tokens.txt", "tokens.txt") to 5_020L,
    )

    fun directory(): File =
        File(System.getProperty("user.home"), ".apachiy/audiosync/asr/$VERSION")

    fun isReady(): Boolean {
        val dir = directory()
        return files.all { (names, size) -> File(dir, names.first).length() == size }
    }

    fun ensureDownloaded(): Boolean {
        if (isReady()) return true
        val dir = directory().apply { mkdirs() }
        for ((names, size) in files) {
            val (local, mirrorName, upstreamName) = names
            val target = File(dir, local)
            if (target.length() == size) continue
            val failure = fetch(MIRROR + mirrorName, target, size)
                ?.let { first -> fetch(UPSTREAM + upstreamName, target, size)?.let { "$first; $it" } }
            if (failure != null) return false
        }
        return isReady()
    }

    private fun fetch(url: String, target: File, expectedSize: Long): String? {
        val partial = File(target.path + ".part")
        return try {
            var connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            var redirects = 0
            while (connection.responseCode in 300..399 && redirects < 5) {
                val location = connection.getHeaderField("Location") ?: break
                connection.disconnect()
                connection = URL(URL(url), location).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 120_000
                redirects++
            }
            if (connection.responseCode != 200) {
                connection.disconnect()
                return "HTTP ${connection.responseCode}"
            }
            connection.inputStream.use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            if (partial.length() != expectedSize) {
                partial.delete()
                return "size ${partial.length()} != $expectedSize"
            }
            if (!partial.renameTo(target)) return "could not save file"
            null
        } catch (failure: Exception) {
            partial.delete()
            failure.message ?: failure.javaClass.simpleName
        }
    }
}
