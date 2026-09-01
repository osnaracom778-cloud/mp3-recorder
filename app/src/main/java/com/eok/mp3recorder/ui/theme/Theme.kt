package com.eok.mp3recorder.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 순흑 배경 + 바이올렛 액센트의 모던 다크 테마 */
private val BlackColors = darkColorScheme(
    primary = Color(0xFF9D8CFF),            // 바이올렛 액센트
    onPrimary = Color(0xFF15121F),
    primaryContainer = Color(0xFF2A2342),
    onPrimaryContainer = Color(0xFFD3C9FF),

    secondary = Color(0xFFA09DB5),
    onSecondary = Color(0xFF191921),
    secondaryContainer = Color(0xFF252332),
    onSecondaryContainer = Color(0xFFDCD9EC),

    tertiary = Color(0xFF6FD9C9),
    onTertiary = Color(0xFF07201C),

    background = Color(0xFF0A0A0D),         // 거의 순흑
    onBackground = Color(0xFFEAE8F2),
    surface = Color(0xFF0A0A0D),
    onSurface = Color(0xFFEAE8F2),
    surfaceVariant = Color(0xFF1C1B23),
    onSurfaceVariant = Color(0xFF9B98AC),

    surfaceContainerLowest = Color(0xFF070709),
    surfaceContainerLow = Color(0xFF111015),
    surfaceContainer = Color(0xFF15141A),   // 하단 탭 바
    surfaceContainerHigh = Color(0xFF1C1B22),
    surfaceContainerHighest = Color(0xFF24232B),

    error = Color(0xFFFF5C6B),              // 녹음 레드
    onError = Color(0xFF2B0509),
    errorContainer = Color(0xFF3B1117),
    onErrorContainer = Color(0xFFFFB9BF),

    outline = Color(0xFF3B3946),
    outlineVariant = Color(0xFF262430),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BlackColors,
        shapes = AppShapes,
        content = content,
    )
}
