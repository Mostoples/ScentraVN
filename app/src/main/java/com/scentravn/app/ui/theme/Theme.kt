package com.scentravn.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * App color palette inspired by biosignal monitor displays: deep navy
 * background with cyan/teal accents for "data" and warm orange for warnings.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF006B6B),
    secondary = Color(0xFF4F6363),
    tertiary = Color(0xFFFF8A65),
    background = Color(0xFFF7FAFC),
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    secondary = Color(0xFF81C7BB),
    tertiary = Color(0xFFFFAB91),
    background = Color(0xFF0B1B2B),
    surface = Color(0xFF13243A),
)

@Composable
fun ScentraVNTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
