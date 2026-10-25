package com.vaani.app.ui.theme

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

val VaaniSaffron = Color(0xFFE8772E)
val VaaniIndigo = Color(0xFF3A3F94)

private val LightColors = lightColorScheme(
    primary = VaaniIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF0F1255),
    secondary = VaaniSaffron,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBC8),
    onSecondaryContainer = Color(0xFF331200),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBDC2FF),
    onPrimary = Color(0xFF232873),
    primaryContainer = VaaniIndigo,
    onPrimaryContainer = Color(0xFFDFE0FF),
    secondary = Color(0xFFFFB68C),
    onSecondary = Color(0xFF532200),
    secondaryContainer = Color(0xFF763300),
    onSecondaryContainer = Color(0xFFFFDBC8),
)

/** Status colours that stay meaningful regardless of dynamic colour. */
object StatusColors {
    val connected = Color(0xFF2E7D32)
    val disconnected = Color(0xFFC62828)
    val checking = Color(0xFF8D8D8D)
}

@Composable
fun VaaniTheme(dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
