package com.nuvio.app.features.player.audiosync

import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal object DesktopSherpaRuntime {
    private val initialized = AtomicBoolean(false)

    fun ensureLoaded(): Boolean {
        if (initialized.get()) return true
        return runCatching {
            loadNativeLibraries()
            initialized.set(true)
            true
        }.getOrDefault(false)
    }

    private fun loadNativeLibraries() {
        val platformDir = when (DesktopHostOs.current) {
            DesktopHostOs.WINDOWS -> "win-x64"
            DesktopHostOs.LINUX -> "linux-x64"
            DesktopHostOs.MACOS -> "osx-aarch64"
            DesktopHostOs.UNKNOWN -> return
        }
        val libraryNames = when (DesktopHostOs.current) {
            DesktopHostOs.WINDOWS -> listOf("onnxruntime.dll", "sherpa-onnx-jni.dll")
            DesktopHostOs.LINUX -> listOf("libonnxruntime.so", "libsherpa-onnx-jni.so")
            DesktopHostOs.MACOS -> listOf("libonnxruntime.dylib", "libsherpa-onnx-jni.dylib")
            DesktopHostOs.UNKNOWN -> emptyList()
        }
        val cacheDir = File(System.getProperty("user.home"), ".apachiy/native/sherpa-$platformDir").apply { mkdirs() }
        for (name in libraryNames) {
            val target = File(cacheDir, name)
            if (!target.isFile || target.length() == 0L) {
                val resource = "/sherpa-onnx/native/$platformDir/$name"
                val stream = DesktopSherpaRuntime::class.java.getResourceAsStream(resource)
                    ?: error("Missing sherpa native resource: $resource")
                stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            }
            System.load(target.absolutePath)
        }
    }
}
