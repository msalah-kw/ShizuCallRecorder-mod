/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Mint, onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF144D47), onPrimaryContainer = Color(0xFFA7F4E3),
    secondary = Color(0xFFAAC8DD), onSecondary = Color(0xFF123244),
    secondaryContainer = Color(0xFF243C4D), onSecondaryContainer = Color(0xFFD3E8F5),
    tertiary = Color(0xFFE7C28C), onTertiary = Color(0xFF422D0B),
    tertiaryContainer = Color(0xFF513E20), onTertiaryContainer = Color(0xFFFFDDA8),
    background = Night, onBackground = Color(0xFFE5EDF3),
    surface = Night, onSurface = Color(0xFFE5EDF3),
    surfaceVariant = Color(0xFF283945), onSurfaceVariant = Color(0xFFAABCC9),
    surfaceContainerLowest = Color(0xFF080F16), surfaceContainerLow = Color(0xFF121F2A),
    surfaceContainer = Color(0xFF172630), surfaceContainerHigh = Color(0xFF20313E),
    surfaceContainerHighest = Color(0xFF293D4B),
    outline = Color(0xFF788E9C), outlineVariant = Color(0xFF2D424F),
    error = Color(0xFFFFB4AA), onError = Color(0xFF690005),
    errorContainer = Color(0xFF5B2429), onErrorContainer = Color(0xFFFFDAD5)
)

private val LightColorScheme = lightColorScheme(
    primary = Teal, onPrimary = Color.White,
    primaryContainer = Color(0xFFCDF4E9), onPrimaryContainer = Color(0xFF063C34),
    secondary = Color(0xFF3D637B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0EDF6), onSecondaryContainer = Color(0xFF153B51),
    tertiary = Color(0xFF765922), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE7BA), onTertiaryContainer = Color(0xFF4A340C),
    background = Cloud, onBackground = Ink,
    surface = Cloud, onSurface = Ink,
    surfaceVariant = Color(0xFFE4ECEF), onSurfaceVariant = Color(0xFF526774),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFECF2F5), surfaceContainerHigh = Color(0xFFE4EDF1),
    surfaceContainerHighest = Color(0xFFDBE6EB),
    outline = Color(0xFF708793), outlineVariant = Color(0xFFD8E3E8),
    error = Color(0xFFAC3441), onError = Color.White,
    errorContainer = Color(0xFFFFE9E8), onErrorContainer = Color(0xFF7C2330)
)

private val RecorderShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun ShizuCallRecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, shapes = RecorderShapes, content = content)
}
