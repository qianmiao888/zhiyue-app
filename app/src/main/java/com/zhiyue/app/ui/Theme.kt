package com.zhiyue.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF12100E)
val InkSoft = Color(0xFF1C1915)
val InkLine = Color(0xFF2C261E)
val Gold = Color(0xFFD8B26A)
val GoldDim = Color(0xFF8B7546)
val Paper = Color(0xFFEDE4D3)
val PaperDim = Color(0xFF9B8F7A)
val Jade = Color(0xFF7FA08A)

private val scheme = darkColorScheme(
    primary = Gold,
    onPrimary = Ink,
    secondary = Jade,
    onSecondary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = InkSoft,
    onSurface = Paper,
    surfaceVariant = InkLine,
    onSurfaceVariant = PaperDim,
)

@Composable
fun ZhiYueTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
