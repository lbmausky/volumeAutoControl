package com.example.volumeautocontrol.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * V2 设计稿只有浅色版，所以这里固定浅色，既不跟随系统深色，也不启用 Material You 动态取色
 * ——动态取色会用壁纸颜色覆盖设计稿的蓝色强调色。
 */
private val LightColorScheme = lightColorScheme(
    primary = Blue500,
    onPrimary = Color.White,
    secondary = Blue600,
    onSecondary = Color.White,
    background = Color.Transparent,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate500,
    outline = Slate300,
    outlineVariant = Slate200,
    errorContainer = Amber100,
    onErrorContainer = Amber600,
)

@Composable
fun VolumeAutoControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content,
    )
}
