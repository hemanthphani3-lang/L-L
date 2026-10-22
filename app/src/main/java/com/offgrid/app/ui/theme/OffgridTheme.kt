package com.offgrid.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RoyalBlue = Color(0xFF2563EB)
val RoyalBlueDark = Color(0xFF1D4ED8)
val EmeraldGreen = Color(0xFF10B981)
val BackgroundWhite = Color(0xFFFFFFFF)
val SurfaceSoft = Color(0xFFF8FAFC)
val SurfaceCard = Color(0xFFFFFFFF)
val BorderGray = Color(0xFFE2E8F0)
val TextPrimary = Color(0xFF0F172A)
val TextSecondary = Color(0xFF64748B)
val AmberWarning = Color(0xFFF59E0B)
val RoseError = Color(0xFFEF4444)

val OffgridColorScheme = lightColorScheme(
    primary = RoyalBlue,
    onPrimary = Color.White,
    secondary = EmeraldGreen,
    onSecondary = Color.White,
    background = BackgroundWhite,
    onBackground = TextPrimary,
    surface = SurfaceSoft,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = TextSecondary,
    outline = BorderGray,
    error = RoseError,
    onError = Color.White
)

@Composable
fun OffgridTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OffgridColorScheme,
        content = content
    )
}
