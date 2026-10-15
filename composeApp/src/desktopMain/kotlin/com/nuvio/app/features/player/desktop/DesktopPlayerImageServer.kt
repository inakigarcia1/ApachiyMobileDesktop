package com.nuvio.app.features.player.desktop

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serves player images to the local WebView. The controls page is a file:// document, so remote
 * <img> URLs fail there. Loopback URLs stay short in the controls payload and each thumbnail
 * loads on its own request.
 */
private val PLAYER_IMAGE_ID_PATTERN = Regex("[a-z0-9]+")

internal object DesktopPlayerImageServer {
    private val images = ConcurrentHashMap<String, ByteArray>()
    private val types = ConcurrentHashMap<String, String>()
    private val idsBySource = ConcurrentHashMap<String, String>()
    private val idSequence = AtomicInteger()
    private val port: Int

    init {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", 0))
        port = socket.localPort
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                Thread {
                    try {
                        serve(client)
                    } catch (_: Exception) {
                    } finally {
                        runCatching { client.close() }
                    }
                }.apply {
                    isDaemon = true
                    name = "player-img-conn"
                    start()
                }
            }
        }, "player-img").apply { isDaemon = true }.start()
    }

    fun publish(sourceUrl: String, type: String, bytes: ByteArray): String {
        val id = idsBySource.computeIfAbsent(sourceUrl) {
            "i" + idSequence.incrementAndGet().toString(36)
        }
        types[id] = type
        images[id] = bytes
        return "http://127.0.0.1:$port/t/$id"
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 2000
        val request = socket.getInputStream().bufferedReader().readLine().orEmpty()
        val id = request.substringAfter("GET /t/", "")
            .substringBefore(" ")
            .substringBefore("?")
        val bytes = id.takeIf { it.matches(PLAYER_IMAGE_ID_PATTERN) }?.let { images[it] }
        if (bytes == null) {
            write(socket, 404, "text/plain", ByteArray(0))
            return
        }
        write(socket, 200, types[id] ?: "image/jpeg", bytes)
    }

    private fun write(socket: Socket, code: Int, type: String, body: ByteArray) {
        val status = if (code == 200) "OK" else "Not Found"
        val safeType = type.substringBefore(";").trim().ifBlank { "application/octet-stream" }
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
            append("Content-Type: ").append(safeType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: private, max-age=86400\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }
}
