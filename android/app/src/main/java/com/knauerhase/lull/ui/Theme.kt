package com.knauerhase.lull.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.knauerhase.lull.bedjet.Mode

// Colors from Lull/Theme.swift in the iOS app, so the two apps look alike.
object LullColors {
    val Background = Color.Black
    val Surface = Color(0xFF0E0F12)
    val Raised = Color(0xFF191A1E)
    val Hairline = Color.White.copy(alpha = 0.08f)
    val Secondary = Color.White.copy(alpha = 0.55f)
    val Tertiary = Color.White.copy(alpha = 0.32f)
    val Cool = Color(0xFF4DA3FF)
    val DeepCool = Color(0xFF2B5CFF)
    val Warm = Color(0xFFFF8A3D)
    val Hot = Color(0xFFFF4D2E)
    val GentleHeat = Color(0xFFFFB240)
    val Dry = Color(0xFF59D6C4)
    val Neutral = Color(0xFF8F8F99)
}

/** The accent color for a mode, as in `Mode.tint` on iOS. */
fun Mode.tint(): Color = when (this) {
    Mode.COOL -> LullColors.Cool
    Mode.HEAT -> LullColors.Warm
    Mode.TURBO -> LullColors.Hot
    Mode.EXTENDED_HEAT -> LullColors.GentleHeat
    Mode.DRY -> LullColors.Dry
    Mode.STANDBY, Mode.WAIT -> LullColors.Neutral
}

@Composable
fun LullTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = LullColors.Background,
            surface = LullColors.Surface,
            surfaceVariant = LullColors.Raised,
            onBackground = Color.White,
            onSurface = Color.White,
            primary = Color.White,
            onPrimary = Color.Black,
        ),
    ) {
        // Without this, text with no explicit color is black, which disappears on the black background.
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}
