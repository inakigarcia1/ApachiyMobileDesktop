package com.nuvio.app.desktop

import com.nuvio.app.core.deeplink.handleAppUrl
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.desktop.AwtNativeViewResolver
import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.awt.Window
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

internal object DesktopSingleInstance {
    private val mainWindow = AtomicReference<Window?>(null)
    private var lockChannel: FileChannel? = null
    private var serverSocket: ServerSocket? = null

    private val lockFile = DesktopStorage.rootDir.resolve("instance.lock")
    private val portFile = DesktopStorage.rootDir.resolve("instance.port")
    private val hwndFile = DesktopStorage.rootDir.resolve("instance.hwnd")

    fun tryBecomePrimaryOrExit(args: Array<String>) {
        if (!shouldEnforceSingleInstance()) return

        val channel = runCatching {
            FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
        }.getOrElse {
            forwardToRunningInstance(args)
            exitProcess(0)
        }

        val lock = channel.tryLock()
        if (lock == null) {
            runCatching { channel.close() }
            forwardToRunningInstance(args)
            exitProcess(0)
        }

        lockChannel = channel
        startInstanceServer()
    }

    fun registerMainWindow(window: Window) {
        if (!shouldEnforceSingleInstance()) return
        mainWindow.set(window)
        if (DesktopHostOs.current == DesktopHostOs.WINDOWS && window.isDisplayable) {
            runCatching {
                val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
                DesktopStorage.rootDir.also { java.nio.file.Files.createDirectories(it) }
                java.nio.file.Files.writeString(hwndFile, hwnd.toString())
            }
        }
    }

    fun clearMainWindow(window: Window) {
        mainWindow.compareAndSet(window, null)
    }

    private fun shouldEnforceSingleInstance(): Boolean {
        if (System.getProperty("compose.application.is.packaged") != "true") return false
        val smokeUrl = System.getProperty("nuvio.desktop.smokePlayerUrl")
            ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
        if (!smokeUrl.isNullOrBlank()) return false
        return true
    }

    private fun startInstanceServer() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        java.nio.file.Files.createDirectories(DesktopStorage.rootDir)
        java.nio.file.Files.writeString(portFile, server.localPort.toString())
        Thread(
            {
                while (!server.isClosed) {
                    runCatching {
                        val socket = server.accept()
                        handleClient(socket)
                    }
                }
            },
            "apachiy-desktop-instance-server",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val input = DataInputStream(client.getInputStream())
            val argCount = input.readInt()
            val args = Array(argCount) { input.readUTF() }
            args.map(String::trim)
                .filter(::isDesktopAppUrl)
                .forEach(::handleAppUrl)
            bringMainWindowToFront()
        }
    }

    private fun bringMainWindowToFront() {
        val window = mainWindow.get()
        if (window != null) {
            java.awt.EventQueue.invokeLater {
                window.isVisible = true
                window.toFront()
                window.requestFocus()
                if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
                    runCatching {
                        val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
                        focusWindowsWindow(hwnd)
                    }
                }
            }
            return
        }
        if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
            val hwnd = runCatching {
                java.nio.file.Files.readString(hwndFile).trim().toLongOrNull()
            }.getOrNull()
            if (hwnd != null) {
                focusWindowsWindow(hwnd)
            }
        }
    }

    private fun forwardToRunningInstance(args: Array<String>) {
        val port = waitForInstancePort()
        if (port == null) return
        runCatching {
            Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                val output = DataOutputStream(socket.getOutputStream())
                output.writeInt(args.size)
                args.forEach(output::writeUTF)
                output.flush()
            }
        }
    }

    private fun waitForInstancePort(): Int? {
        repeat(40) {
            val port = runCatching {
                java.nio.file.Files.readString(portFile).trim().toInt()
            }.getOrNull()
            if (port != null && port > 0) return port
            Thread.sleep(50)
        }
        return null
    }
}

private fun isDesktopAppUrl(value: String): Boolean =
    value.startsWith("apachiy://", ignoreCase = true) ||
        value.startsWith("nuvio://", ignoreCase = true) ||
        value.startsWith("stremio://", ignoreCase = true)
