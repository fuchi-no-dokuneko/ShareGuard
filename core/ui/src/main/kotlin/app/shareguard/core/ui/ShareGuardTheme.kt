package app.shareguard.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF172026)
private val Paper = Color(0xFFF8FAFB)
private val Slate = Color(0xFF52616B)
private val Cyan = Color(0xFF006A72)
private val CyanContainer = Color(0xFF9CF0FA)
private val Amber = Color(0xFF765A00)
private val AmberContainer = Color(0xFFFFE16D)
private val Red = Color(0xFFBA1A1A)

private val LightColors = lightColorScheme(
    primary = Cyan,
    onPrimary = Color.White,
    primaryContainer = CyanContainer,
    onPrimaryContainer = Color(0xFF002022),
    secondary = Slate,
    error = Red,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE8EEF0),
    onSurfaceVariant = Color(0xFF40494D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4ED8E4),
    onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF004F56),
    onPrimaryContainer = CyanContainer,
    secondary = Color(0xFFB8C8CE),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF101416),
    onBackground = Color(0xFFE0E3E5),
    surface = Color(0xFF171C1E),
    onSurface = Color(0xFFE0E3E5),
    surfaceVariant = Color(0xFF3F484C),
    onSurfaceVariant = Color(0xFFBFC8CC),
)

object ShareGuardColors {
    val warning: Color = Amber
    val warningContainer: Color = AmberContainer
    val destructive: Color = Red
}

@Composable
fun ShareGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
