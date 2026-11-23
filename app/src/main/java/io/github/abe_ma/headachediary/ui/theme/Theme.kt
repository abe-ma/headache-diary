package io.github.abe_ma.headachediary.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 頭痛中は光がつらいので、ダークは純黒ではなく落ち着いた濃いグレー、彩度を抑えた配色にする。
// 文字と背景のコントラストは WCAG AA (4.5:1) 以上を目安にしている。
private val LightColors = lightColorScheme(
    primary = Color(0xFF2B5C8A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E4F8),
    onPrimaryContainer = Color(0xFF0B2842),
    secondaryContainer = Color(0xFFDCE3EC),
    onSecondaryContainer = Color(0xFF141C24),
    background = Color(0xFFFBFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFBFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE0E3E8),
    onSurfaceVariant = Color(0xFF42474E),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC7F2),
    onPrimary = Color(0xFF0B2842),
    primaryContainer = Color(0xFF244A6F),
    onPrimaryContainer = Color(0xFFD3E4F8),
    secondaryContainer = Color(0xFF3A4452),
    onSecondaryContainer = Color(0xFFDCE3EC),
    background = Color(0xFF121417),
    onBackground = Color(0xFFE2E4E8),
    surface = Color(0xFF121417),
    onSurface = Color(0xFFE2E4E8),
    surfaceVariant = Color(0xFF2A2E34),
    onSurfaceVariant = Color(0xFFC3C7CE),
    error = Color(0xFFF2B8B5),
)

@Composable
fun HeadacheDiaryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
