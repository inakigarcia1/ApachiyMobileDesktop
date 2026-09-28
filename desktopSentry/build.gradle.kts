import java.util.Properties

plugins {
    java
    alias(libs.plugins.sentry.jvm.gradle)
}

val localProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}

fun envOrLocalProperty(key: String): String? =
    providers.environmentVariable(key).orNull?.trim()?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }

val sentryAuthToken = envOrLocalProperty("SENTRY_AUTH_TOKEN")
val sentryUploadEnabled = sentryAuthToken != null && envOrLocalProperty("SENTRY_ORG") == "apachiy"

sentry {
    includeSourceContext.set(true)
    autoUploadSourceContext.set(sentryUploadEnabled)
    additionalSourceDirsForSourceContext.set(
        setOf(
            "../composeApp/src/commonMain/kotlin",
            "../composeApp/src/desktopMain/kotlin",
            "../composeApp/src/fullCommonMain/kotlin",
        ),
    )
    includeDependenciesReport.set(false)
    telemetry.set(false)
    org.set("apachiy")
    projectName.set("apachiy-desktop")
    sentryAuthToken?.let(authToken::set)
    autoInstallation {
        enabled.set(false)
    }
}
