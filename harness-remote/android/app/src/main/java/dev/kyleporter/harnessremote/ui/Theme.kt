package dev.kyleporter.harnessremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Black = Color(0xFF080808)
val Surface = Color(0xFF111111)
val SurfaceHigh = Color(0xFF1A1A1A)
val Red = Color(0xFFE63946)
val OffWhite = Color(0xFFF2EEEA)
val Gray = Color(0xFF888888)
val Green = Color(0xFF3DDC84)
val Amber = Color(0xFFFFB020)

private val scheme = darkColorScheme(
    primary = Red,
    onPrimary = OffWhite,
    secondary = Red,
    onSecondary = OffWhite,
    background = Black,
    onBackground = OffWhite,
    surface = Surface,
    onSurface = OffWhite,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Gray,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHigh,
    outline = Color(0xFF333333),
    error = Red,
)

@Composable
fun HarnessTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

val Mono = FontFamily.Monospace

fun stateColor(state: String): Color = when (state) {
    "running" -> Green
    "waiting" -> Amber
    "exited" -> Gray
    else -> Gray
}

fun stateLabel(state: String): String = when (state) {
    "running" -> "Running"
    "waiting" -> "Needs input"
    "exited" -> "Exited"
    "idle" -> "Idle"
    else -> state
}

@Composable
fun StateChip(state: String) {
    val color = stateColor(state)
    Row(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(stateLabel(state), color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
