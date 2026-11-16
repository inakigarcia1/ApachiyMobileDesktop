package com.nuvio.app.features.autosync

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import java.io.File
import java.time.LocalTime

/** Debug builds: mirror AutoSync and desktop subtitle logs to `%TEMP%/apachiy-autosync-trace.log`. */
internal object AutoSyncTraceFile : LogWriter() {
    private val tags = setOf(AutoSyncTrace.TAG, "DesktopAudioSync", "NativePlayerControls")
    val file = File(System.getProperty("java.io.tmpdir"), "apachiy-autosync-trace.log")

    fun install() {
        runCatching { file.writeText("") }
        Logger.addLogWriter(this)
        Logger.withTag(AutoSyncTrace.TAG).i { "trace file: ${file.absolutePath}" }
    }

    override fun isLoggable(tag: String, severity: Severity): Boolean = tag in tags

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        // NativePlayerControls is chatty; keep only its subtitle lines.
        if (tag == "NativePlayerControls" && !message.contains("ubtitle", ignoreCase = true)) return
        val line = "${LocalTime.now()} [$tag] $message${throwable?.let { " :: $it" } ?: ""}\n"
        synchronized(this) { runCatching { file.appendText(line) } }
    }
}
