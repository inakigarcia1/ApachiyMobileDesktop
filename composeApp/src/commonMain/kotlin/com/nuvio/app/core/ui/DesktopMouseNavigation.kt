package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier

internal expect fun Modifier.consumeDesktopMouseNavigationButtons(): Modifier

internal expect fun Modifier.desktopMouseBackNavigation(onBack: () -> Unit): Modifier
