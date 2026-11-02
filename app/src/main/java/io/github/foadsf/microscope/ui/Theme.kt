package io.github.foadsf.microscope.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** A dark instrument palette: near-black, cool greys, one teal accent. */
object Palette {
    val Background = Color(0xFF07090C)
    val Surface = Color(0xFF11161D)
    val SurfaceHigh = Color(0xFF1A212B)
    val Outline = Color(0xFF2A333F)
    val Text = Color(0xFFE6EDF3)
    val TextDim = Color(0xFF8B98A9)
    val Accent = Color(0xFF5EEAD4)
    val AccentDim = Color(0xFF1F4F49)
    val Warn = Color(0xFFFBBF24)
    val Scrim = Color(0x99000000)
}

object Type {
    val Label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, color = Palette.TextDim)
    val Value = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum", color = Palette.Text)
    val Small = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum", color = Palette.TextDim)
    val Tab = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
}

@Composable
fun MicroscopeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Palette.Background,
            background = Palette.Background,
            surface = Palette.Surface,
            onSurface = Palette.Text,
            onBackground = Palette.Text,
        ),
        content = content,
    )
}
