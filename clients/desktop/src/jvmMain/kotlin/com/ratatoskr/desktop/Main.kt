package com.ratatoskr.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ratatoskr.shared.ui.RatatoskrApp
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Dimension

/**
 * Desktop (Linux/Windows/macOS, since the one Compose Desktop module builds
 * installers for all three, see docs/development-plan.md) entry point.
 * All actual app logic and every screen live in :shared and are unchanged
 * here; this file's only job is the desktop-specific plumbing Compose
 * Multiplatform can't provide generically: the window itself and loading
 * a bitmap icon from a classpath resource (JVM-specific; Android/iOS will
 * supply RatatoskrApp's `appIcon` parameter their own way in their
 * phases, without any change needed in :shared).
 */
fun main() = application {
    val windowState = rememberWindowState(
        position = WindowPosition.Aligned(Alignment.Center),
        // Wide enough for the vault toolbar (search + Export/Import/New
        // entry) with room to spare. The web UI's vault column is 720px.
        size = DpSize(820.dp, 760.dp),
    )

    val iconPainter = remember {
        val bytes = checkNotNull(
            Thread.currentThread().contextClassLoader.getResourceAsStream("icons/ratatoskr_512.png")
        ) { "icons/ratatoskr_512.png missing from classpath" }.use { it.readBytes() }
        BitmapPainter(SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap())
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Ratatoskr",
        state = windowState,
        icon = iconPainter,
    ) {
        // Below this the toolbar buttons squeeze the search field unusably.
        LaunchedEffect(Unit) { window.minimumSize = Dimension(640, 520) }
        val files = remember { DesktopFiles(window) }
        val sessionStore = remember { DesktopSessionStore() }
        RatatoskrApp(
            files = files,
            appIcon = iconPainter,
            sessionStore = sessionStore,
        )
    }
}
