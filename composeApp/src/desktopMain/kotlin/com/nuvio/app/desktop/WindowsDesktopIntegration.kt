package com.nuvio.app.desktop

import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary

/** Must match jpackage `packageName` / shortcut `System.AppUserModel.ID` ([composeApp/build.gradle.kts]). */
internal const val WINDOWS_APP_USER_MODEL_ID = "Apachiy"

internal fun configureWindowsAppUserModelId() {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return
    runCatching {
        Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(WString(WINDOWS_APP_USER_MODEL_ID))
    }
}

internal fun focusWindowsWindow(hwnd: Long) {
    if (hwnd == 0L) return
    val handle = Pointer(hwnd)
    User32.INSTANCE.ShowWindow(handle, WinUser.SW_RESTORE)
    User32.INSTANCE.SetForegroundWindow(handle)
}

private object WinUser {
    const val SW_RESTORE = 9
}

private interface Shell32 : StdCallLibrary {
    fun SetCurrentProcessExplicitAppUserModelID(appId: WString): Int

    companion object {
        val INSTANCE: Shell32 = Native.load("shell32", Shell32::class.java)
    }
}

private interface User32 : StdCallLibrary {
    fun ShowWindow(hWnd: Pointer, nCmdShow: Int): Boolean
    fun SetForegroundWindow(hWnd: Pointer): Boolean

    companion object {
        val INSTANCE: User32 = Native.load("user32", User32::class.java)
    }
}
