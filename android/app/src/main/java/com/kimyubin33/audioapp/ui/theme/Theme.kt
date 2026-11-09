package com.kimyubin33.audioapp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// 디자인 원칙: 먹과 화선지. 흑백과 그 사이의 번짐만 쓰고, 극도로 단순하게.
// 시스템 다크 모드·동적 색상은 쓰지 않는다 (종이 위에 먹, 이 한 가지 모습만).
private val InkOnPaperColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    secondary = InkSoft,
    onSecondary = Paper,
    tertiary = InkSoft,
    onTertiary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Mist,
    onSurfaceVariant = Ash,
    surfaceContainer = PaperDeep,
    surfaceContainerHigh = Paper,
    outline = Mist,
    outlineVariant = Mist,
)

// 먹으로 쓴 듯 둥글지 않고 단정한 모서리
private val InkShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun AudioAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = InkOnPaperColorScheme,
        typography = Typography,
        shapes = InkShapes,
        content = content,
    )
}
