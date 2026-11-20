package com.fieldpartner.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One restrained palette: a deep navy for primary actions, neutral grays for everything else,
 * amber only for "needs attention" and red only for STOP. No gradients, no decorative color.
 */
object FpColors {
  val Navy = Color(0xFF143A5A)
  val NavyLight = Color(0xFFDCE6F0)
  val Ink = Color(0xFF1B1F24)
  val Muted = Color(0xFF5B6570)
  val Line = Color(0xFFD5DBE1)
  val Canvas = Color(0xFFF6F7F9)
  val Success = Color(0xFF2E7D32)
  val Warning = Color(0xFF9A5B00)
  val WarningBg = Color(0xFFFFF4E0)
  val Danger = Color(0xFFB3261E)
  val Info = Color(0xFF143A5A)
}

private val Light =
  lightColorScheme(
    primary = FpColors.Navy,
    onPrimary = Color.White,
    primaryContainer = FpColors.NavyLight,
    onPrimaryContainer = FpColors.Navy,
    secondary = Color(0xFF46566A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E8EE),
    onSecondaryContainer = FpColors.Ink,
    tertiary = FpColors.Warning,
    onTertiary = Color.White,
    tertiaryContainer = FpColors.WarningBg,
    onTertiaryContainer = Color(0xFF4A2C00),
    error = FpColors.Danger,
    onError = Color.White,
    background = FpColors.Canvas,
    onBackground = FpColors.Ink,
    surface = Color.White,
    onSurface = FpColors.Ink,
    surfaceVariant = Color(0xFFEDF0F3),
    onSurfaceVariant = FpColors.Muted,
    surfaceContainer = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFF1F3F5),
    outline = Color(0xFF8A939C),
    outlineVariant = FpColors.Line,
  )

private val Dark =
  darkColorScheme(
    primary = Color(0xFF9CC3E6),
    onPrimary = Color(0xFF0B2236),
    primaryContainer = Color(0xFF1E4566),
    onPrimaryContainer = Color(0xFFDCE6F0),
    secondary = Color(0xFFB7C4D2),
    tertiary = Color(0xFFFFB95C),
    onTertiary = Color(0xFF3A2400),
    tertiaryContainer = Color(0xFF3D2A0A),
    error = Color(0xFFF2B8B5),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE3E6EA),
    surface = Color(0xFF171B20),
    onSurface = Color(0xFFE3E6EA),
    surfaceVariant = Color(0xFF242A31),
    onSurfaceVariant = Color(0xFFB9C1CA),
    surfaceContainer = Color(0xFF1B2026),
    surfaceContainerHigh = Color(0xFF222830),
    outline = Color(0xFF7D8791),
    outlineVariant = Color(0xFF333A42),
  )

private val Type =
  Typography().run {
    copy(
      headlineLarge = headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
      titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
      titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
      labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
  }

private val Shape = Shapes(small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(14.dp))

@Composable
fun FpTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, shapes = Shape, content = content)
}

enum class Status { OK, PENDING, WARN, BAD }

/** A status icon from Material Symbols. Never text glyphs or emoji. */
@Composable
fun StatusIcon(status: Status, modifier: Modifier = Modifier.size(20.dp)) {
  val (icon: ImageVector, tint: Color) =
    when (status) {
      Status.OK -> Icons.Filled.CheckCircle to FpColors.Success
      Status.PENDING -> Icons.Outlined.RadioButtonUnchecked to MaterialTheme.colorScheme.outline
      Status.WARN -> Icons.Filled.Warning to FpColors.Warning
      Status.BAD -> Icons.Filled.Error to FpColors.Danger
    }
  Icon(icon, contentDescription = status.name.lowercase(), tint = tint, modifier = modifier)
}

/** Icon + text on one line, for buttons and list rows. */
@Composable
fun IconText(icon: ImageVector, text: String, style: TextStyle = MaterialTheme.typography.labelLarge) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text, style = style)
  }
}
