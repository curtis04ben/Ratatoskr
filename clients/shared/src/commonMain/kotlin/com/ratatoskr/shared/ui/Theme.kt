package com.ratatoskr.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Exact color values lifted from static/style.css's :root custom
 * properties, so the desktop/Android/iOS apps read as the same product as
 * the web UI rather than a reinterpretation of it. Ratatoskr is dark-first
 * by design (the whole ink/brass/Norse identity assumes a dark backdrop),
 * so unlike a typical app this deliberately does not offer a light theme.
 */
object RatatoskrColors {
    val Bg = Color(0xFF12181C)
    val Panel = Color(0xFF182027)
    val PanelAlt = Color(0xFF1D262D)
    val Hairline = Color(0xFF2B363D)
    val Text = Color(0xFFE9E6DD)
    val TextMuted = Color(0xFF8FA0A8)
    val Brass = Color(0xFFB98A4E)
    val BrassBright = Color(0xFFD6A868)
    val Verdigris = Color(0xFF5E9E8A)
    val Danger = Color(0xFFC4634A)
    val DangerBright = Color(0xFFDA7B60)
}

private val RatatoskrColorScheme = darkColorScheme(
    background = RatatoskrColors.Bg,
    surface = RatatoskrColors.Panel,
    surfaceVariant = RatatoskrColors.PanelAlt,
    primary = RatatoskrColors.Brass,
    onPrimary = RatatoskrColors.Bg,
    secondary = RatatoskrColors.Verdigris,
    onSecondary = RatatoskrColors.Bg,
    error = RatatoskrColors.Danger,
    onError = RatatoskrColors.Text,
    onBackground = RatatoskrColors.Text,
    onSurface = RatatoskrColors.Text,
    outline = RatatoskrColors.Hairline,
)

@Composable
fun RatatoskrTheme(content: @Composable () -> Unit) {
    // isSystemInDarkTheme() is read but intentionally unused for branching.
    // See the comment above; kept as a named call rather than removed so a
    // future light-theme decision has an obvious place to plug in.
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = RatatoskrColorScheme,
        content = content,
    )
}
