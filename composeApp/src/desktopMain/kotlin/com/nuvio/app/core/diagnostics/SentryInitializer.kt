package com.nuvio.app.core.diagnostics

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.settings.SentrySettingsRepository
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

object SentryInitializer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    @Volatile
    private var active = false

    @Synchronized
    fun start() {
        if (started || !SentrySettingsRepository.isSupported) return
        started = true
        SentrySettingsRepository.ensureLoaded()
        applyEnabled(SentrySettingsRepository.enabled.value)
        scope.launch {
            SentrySettingsRepository.enabled.drop(1).collect(::applyEnabled)
        }
    }

    @Synchronized
    fun close() {
        scope.cancel()
        if (Sentry.isEnabled()) {
            Sentry.close()
        }
        active = false
    }

    @Synchronized
    private fun applyEnabled(enabled: Boolean) {
        if (!enabled) {
            if (Sentry.isEnabled()) {
                Sentry.close()
            }
            active = false
            return
        }

        val dsn = SentryConfig.DESKTOP_DSN.trim()
        if (dsn.isBlank() || active && Sentry.isEnabled()) return

        val metadata = currentDesktopSentryMetadata()
        val initialized = runCatching {
            Sentry.init { options ->
                configureOptions(options, dsn, metadata)
            }
        }.isSuccess && Sentry.isEnabled()
        if (!initialized) return

        Sentry.configureScope { sentryScope ->
            sentryScope.setTag("app.package_name", "com.apachiy.desktop")
            sentryScope.setTag("app.version_name", AppVersionConfig.DESKTOP_VERSION_NAME)
            sentryScope.setTag("app.version_code", AppVersionConfig.DESKTOP_VERSION_CODE.toString())
            sentryScope.setTag("desktop.platform", metadata.platform)
            sentryScope.setTag("desktop.architecture", metadata.architecture)
        }
        Sentry.addBreadcrumb(
            Breadcrumb.info("Desktop application started").apply {
                category = "app.lifecycle"
            },
        )
        active = true
    }

    private fun configureOptions(
        options: SentryOptions,
        dsn: String,
        metadata: DesktopSentryMetadata,
    ) {
        options.dsn = dsn
        options.release = metadata.release
        options.dist = metadata.distribution
        options.environment = SentryConfig.ENVIRONMENT
        options.isSendDefaultPii = false
        options.isAttachServerName = false
        options.isEnableExternalConfiguration = false
        options.isEnableUncaughtExceptionHandler = true
        options.isEnableShutdownHook = true
        options.isEnableEventSizeLimiting = true
        options.tracesSampleRate = 0.0
        options.setMaxBreadcrumbs(50)
        options.cacheDirPath = DesktopStorage.cacheDir.resolve("sentry").toString()
        options.addInAppInclude("com.nuvio.app")
        options.setIgnoredErrors(SentryEventSanitizer.ignoredIssueText)
        options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
            SentryEventSanitizer.sanitize(event)
        }
    }

    internal fun reportUnexpectedSignOut(reason: String) {
        if (!active || !Sentry.isEnabled()) return
        Sentry.captureMessage("unexpected_sign_out: $reason", SentryLevel.ERROR)
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
