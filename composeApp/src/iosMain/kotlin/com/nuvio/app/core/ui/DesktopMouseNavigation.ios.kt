package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier

internal actual fun Modifier.consumeDesktopMouseNavigationButtons(): Modifier = this

internal actual fun Modifier.desktopMouseBackNavigation(onBack: () -> Unit): Modifier = this
