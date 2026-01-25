package com.zerochat.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Privacy-focused dark color scheme
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF58A6FF),
    onPrimary = Color(0xFF0D1117),
    primaryContainer = Color(0xFF1F6FEE),
    onPrimaryContainer = Color(0xFFE6EDF3),
    
    secondary = Color(0xFF8B949E),
    onSecondary = Color(0xFF0D1117),
    secondaryContainer = Color(0xFF30363D),
    onSecondaryContainer = Color(0xFFE6EDF3),
    
    tertiary = Color(0xFF3FB950),
    onTertiary = Color(0xFF0D1117),
    
    error = Color(0xFFF85149),
    onError = Color(0xFF0D1117),
    
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF8B949E),
    
    outline = Color(0xFF30363D),
    outlineVariant = Color(0xFF21262D),
)

@Composable
fun ZeroChatTheme(
    content: @Composable () -> Unit
) {
    // Force dark theme - no light mode for privacy
    val colorScheme = DarkColorScheme
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
