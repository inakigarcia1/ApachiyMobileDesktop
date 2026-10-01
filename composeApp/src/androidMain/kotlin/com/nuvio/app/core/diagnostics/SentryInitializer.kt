package com.nuvio.app.core.diagnostics

import android.app.Application
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.settings.SentrySettingsRepository
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object SentryInitializer {
    private val droppedIssueText = listOf(
        "Large HTTP payload",
        "File IO on Main Thread",
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    @Volatile
    private var active = false

    fun start(application: Application) {
        if (started || !SentrySettingsRepository.isSupported) return
        started = true
        SentrySettingsRepository.ensureLoaded()
        applyEnabled(application, SentrySettingsRepository.enabled.value)
        scope.launch {
            SentrySettingsRepository.enabled.collect { enabled ->
                applyEnabled(application, enabled)
            }
        }
    }

    private fun applyEnabled(application: Application, enabled: Boolean) {
        if (!enabled) {
            if (Sentry.isEnabled()) {
                Sentry.close()
            }
            active = false
            return
        }

        val dsn = SentryConfig.DSN.trim()
        if (dsn.isBlank()) return
        if (active && Sentry.isEnabled()) return

        SentryAndroid.init(application) { options ->
            options.dsn = dsn
            options.release = "${application.packageName}@${AppVersionConfig.VERSION_NAME}+${AppVersionConfig.VERSION_CODE}"
            options.environment = SentryConfig.ENVIRONMENT
            options.isSendDefaultPii = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.tracesSampleRate = 0.0
            options.setMaxBreadcrumbs(50)
            options.setIgnoredErrors(droppedIssueText)
            options.setIgnoredTransactions(droppedIssueText)
            options.setTraceOptionsRequests(false)
            options.setEnableAutoActivityLifecycleTracing(false)
            options.setEnableTimeToFullDisplayTracing(false)
            options.setEnableFramesTracking(false)
            options.setEnablePerformanceV2(false)
            options.setEnableNetworkEventBreadcrumbs(false)
            options.setReportHistoricalAnrs(false)
            options.setAttachAnrThreadDump(false)
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                event.request = null
                event.user = null
                if (shouldDrop(event)) null else event
            }
        }

        Sentry.configureScope { scope ->
            scope.setTag("app.package_name", application.packageName)
            scope.setTag("app.version_name", AppVersionConfig.VERSION_NAME)
            scope.setTag("app.version_code", AppVersionConfig.VERSION_CODE.toString())
        }
        active = true
    }

    private fun shouldDrop(event: SentryEvent): Boolean =
        eventText(event).any { text ->
            droppedIssueText.any { dropped ->
                text.contains(dropped, ignoreCase = true)
            }
        }

    private fun eventText(event: SentryEvent): List<String> {
        val values = mutableListOf<String>()
        event.message?.formatted?.let(values::add)
        event.message?.message?.let(values::add)
        event.logger?.let(values::add)
        event.transaction?.let(values::add)
        event.exceptions?.forEach { exception ->
            exception.type?.let(values::add)
            exception.value?.let(values::add)
        }
        return values
    }

    internal fun reportPlaybackFailure(report: PlaybackFailureReport) {
        if (!active || !Sentry.isEnabled()) return
        Sentry.captureException(PlaybackFailure(report.summary())) { scope ->
            scope.level = SentryLevel.ERROR
            scope.setFingerprint(report.fingerprint())
            fun tag(key: String, value: String?) {
                val cleaned = sentryTag(value) ?: return
                scope.setTag(key, cleaned)
            }
            fun extra(key: String, value: String?) {
                if (value.isNullOrBlank()) return
                scope.setExtra(key, value)
            }
            tag("playback.engine", report.engine)
            tag("playback.host", report.host)
            tag("playback.provider", report.provider)
            tag("playback.stream_type", report.streamType)
            tag("playback.error_code", report.errorCode)
            tag("playback.exception", report.exceptionClass)
            tag("playback.video_codec", report.videoCodec)
            tag("playback.mime", report.mimeType)
            extra("cause_class", report.causeClass)
            extra("cause_message", report.causeMessage)
            extra("content_id", report.contentId)
            extra("title", report.title)
            extra("season", report.season?.toString())
            extra("episode", report.episode?.toString())
            extra("stream_label", report.streamLabel)
            extra("message", report.message)
        }
    }

    private fun sentryTag(value: String?): String? =
        value?.replace(Regex("\\s+"), " ")?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
}

private class PlaybackFailure(message: String) : Exception(message)
