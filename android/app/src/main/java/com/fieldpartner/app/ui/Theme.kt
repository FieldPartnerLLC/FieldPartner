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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * FieldPartner design system.
 *
 * One blue for actions and links. A light gray canvas with white cards on top of it.
 * Gray secondary buttons. Green only for "done", amber only for "needs attention", red only for STOP.
 * One font family (the system sans, Roboto on Android) at a fixed set of sizes. No gradients.
 */
object FpColors {
  val Blue = Color(0xFF1462E3)
  val BlueDark = Color(0xFF0F4FBF)
  val BlueTint = Color(0xFFE8F0FD)
  val Ink = Color(0xFF1C1E21)
  val Muted = Color(0xFF65676B)
  val Faint = Color(0xFF8A8D91)
  val Line = Color(0xFFE4E6EB)
  val Canvas = Color(0xFFF0F2F5)
  val Chip = Color(0xFFE4E6EB)
  val Success = Color(0xFF1E8E3E)
  val SuccessTint = Color(0xFFE6F4EA)
  val Warning = Color(0xFFB45F06)
  val WarningTint = Color(0xFFFEF3E2)
  val Danger = Color(0xFFD92D20)
  val DangerTint = Color(0xFFFDECEA)
}

/** Neutral tokens that differ between light and dark mode and have no Material3 slot. */
@Immutable
data class FpPalette(
  val canvas: Color,
  val card: Color,
  val chip: Color,
  val line: Color,
  val ink: Color,
  val muted: Color,
  val faint: Color,
  val blueTint: Color,
  val successTint: Color,
  val warningTint: Color,
  val dangerTint: Color,
)

private val LightPalette =
  FpPalette(
    canvas = FpColors.Canvas,
    card = Color.White,
    chip = FpColors.Chip,
    line = FpColors.Line,
    ink = FpColors.Ink,
    muted = FpColors.Muted,
    faint = FpColors.Faint,
    blueTint = FpColors.BlueTint,
    successTint = FpColors.SuccessTint,
    warningTint = FpColors.WarningTint,
    dangerTint = FpColors.DangerTint,
  )

private val DarkPalette =
  FpPalette(
    canvas = Color(0xFF18191A),
    card = Color(0xFF242526),
    chip = Color(0xFF3A3B3C),
    line = Color(0xFF3A3B3C),
    ink = Color(0xFFE4E6EB),
    muted = Color(0xFFB0B3B8),
    faint = Color(0xFF8A8D91),
    blueTint = Color(0xFF1D2B42),
    successTint = Color(0xFF15301E),
    warningTint = Color(0xFF3A2A12),
    dangerTint = Color(0xFF3D1A17),
  )

val LocalFpPalette = staticCompositionLocalOf { LightPalette }

/** Shortcut: `Fp.palette.muted` etc. */
object Fp {
  val palette: FpPalette
    @Composable get() = LocalFpPalette.current
}

private val Light =
  lightColorScheme(
    primary = FpColors.Blue,
    onPrimary = Color.White,
    primaryContainer = FpColors.BlueTint,
    onPrimaryContainer = FpColors.BlueDark,
    secondary = FpColors.Muted,
    onSecondary = Color.White,
    secondaryContainer = FpColors.Chip,
    onSecondaryContainer = FpColors.Ink,
    tertiary = FpColors.Warning,
    onTertiary = Color.White,
    tertiaryContainer = FpColors.WarningTint,
    onTertiaryContainer = Color(0xFF4A2C00),
    error = FpColors.Danger,
    onError = Color.White,
    errorContainer = FpColors.DangerTint,
    background = FpColors.Canvas,
    onBackground = FpColors.Ink,
    surface = Color.White,
    onSurface = FpColors.Ink,
    surfaceVariant = FpColors.Canvas,
    onSurfaceVariant = FpColors.Muted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = FpColors.Chip,
    outline = Color(0xFFCED0D4),
    outlineVariant = FpColors.Line,
  )

private val Dark =
  darkColorScheme(
    primary = Color(0xFF5B9BFF),
    onPrimary = Color(0xFF07162E),
    primaryContainer = DarkPalette.blueTint,
    onPrimaryContainer = Color(0xFFCFE0FF),
    secondary = DarkPalette.muted,
    onSecondary = Color.Black,
    secondaryContainer = DarkPalette.chip,
    onSecondaryContainer = DarkPalette.ink,
    tertiary = Color(0xFFF5B25A),
    onTertiary = Color(0xFF3A2400),
    tertiaryContainer = DarkPalette.warningTint,
    onTertiaryContainer = Color(0xFFFFDDB0),
    error = Color(0xFFFF6B5E),
    onError = Color.Black,
    errorContainer = DarkPalette.dangerTint,
    background = DarkPalette.canvas,
    onBackground = DarkPalette.ink,
    surface = DarkPalette.card,
    onSurface = DarkPalette.ink,
    surfaceVariant = DarkPalette.canvas,
    onSurfaceVariant = DarkPalette.muted,
    surfaceContainerLowest = DarkPalette.card,
    surfaceContainerLow = DarkPalette.card,
    surfaceContainer = DarkPalette.card,
    surfaceContainerHigh = DarkPalette.card,
    surfaceContainerHighest = DarkPalette.chip,
    outline = Color(0xFF555759),
    outlineVariant = DarkPalette.line,
  )

/**
 * The only text sizes in the app. Screens pick from these and never set sizes or weights by hand.
 * headlineSmall: big screen titles. titleLarge: page titles. titleMedium: card titles.
 * titleSmall: row titles. bodyLarge/bodyMedium: reading text. bodySmall: meta lines. label*: buttons, tags.
 */
private val Sans = FontFamily.Default

private val Type =
  Typography(
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
  )

private val Shape = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(16.dp))

@Composable
fun FpTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalFpPalette provides if (dark) DarkPalette else LightPalette) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, shapes = Shape, content = content)
  }
}

enum class Status { OK, PENDING, WARN, BAD }

/** A status icon from Material Symbols. Never text glyphs or emoji. */
@Composable
fun StatusIcon(status: Status, modifier: Modifier = Modifier.size(20.dp)) {
  val (icon: ImageVector, tint: Color) =
    when (status) {
      Status.OK -> Icons.Filled.CheckCircle to FpColors.Success
      Status.PENDING -> Icons.Outlined.RadioButtonUnchecked to Fp.palette.faint
      Status.WARN -> Icons.Filled.Warning to FpColors.Warning
      Status.BAD -> Icons.Filled.Error to FpColors.Danger
    }
  Icon(icon, contentDescription = status.name.lowercase(), tint = tint, modifier = modifier)
}

/** Icon + text on one line, for buttons and list rows. */
@Composable
fun IconText(icon: ImageVector, text: String, style: TextStyle = MaterialTheme.typography.labelLarge) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
    Text(text, style = style)
  }
}
