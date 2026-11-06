package com.kimyubin33.audioapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// 디자인 원칙: 흰 배경 + 검정, 극도로 단순하게. 시스템 다크 모드·동적 색상은 쓰지 않는다.
private val InkOnPaperColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    secondary = Ink,
    onSecondary = Paper,
    tertiary = Ink,
    onTertiary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Mist,
    onSurfaceVariant = Ash,
    outline = Mist,
    outlineVariant = Mist,
)

@Composable
fun AudioAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = InkOnPaperColorScheme,
        typography = Typography,
        content = content
    )
}
