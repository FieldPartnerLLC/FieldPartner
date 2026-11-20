package com.fieldpartner.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---- Page frame ------------------------------------------------------------------------------------

/** The three main tabs. Detail screens hide the tab bar and show a back arrow instead. */
enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val screen: Screen) {
  JOBS("Jobs", Icons.Outlined.Home, Icons.Filled.Home, Screen.Jobs),
  GLASSES("Glasses", Icons.Outlined.Visibility, Icons.Filled.Visibility, Screen.Glasses),
  MENU("Menu", Icons.Filled.Menu, Icons.Filled.Menu, Screen.Settings),
}

/**
 * Every screen uses this frame: a white top bar with a hairline under it, the gray canvas below.
 * [bigTitle] is for the main tabs (large, left aligned, like a home feed); detail screens get a
 * normal title with a back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FpScaffold(
  title: String,
  onBack: (() -> Unit)? = null,
  bigTitle: Boolean = false,
  titleColor: Color? = null,
  tab: Tab? = null,
  onTab: ((Tab) -> Unit)? = null,
  bottomBar: @Composable () -> Unit = {},
  actions: @Composable RowScope.() -> Unit = {},
  content: @Composable (Modifier) -> Unit,
) {
  Scaffold(
    containerColor = Fp.palette.canvas,
    topBar = {
      Column {
        TopAppBar(
          colors = TopAppBarDefaults.topAppBarColors(containerColor = Fp.palette.card, titleContentColor = titleColor ?: Fp.palette.ink, navigationIconContentColor = Fp.palette.ink),
          title = { Text(title, style = if (bigTitle) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge, color = titleColor ?: Fp.palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
          actions = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) { actions() } },
        )
        HorizontalDivider(color = Fp.palette.line)
      }
    },
    bottomBar = {
      if (tab != null && onTab != null) TabBar(tab, onTab) else bottomBar()
    },
  ) { pad -> content(Modifier.padding(pad).fillMaxSize()) }
}

@Composable
fun TabBar(selected: Tab, onTab: (Tab) -> Unit) {
  Column {
    HorizontalDivider(color = Fp.palette.line)
    NavigationBar(containerColor = Fp.palette.card, tonalElevation = 0.dp, modifier = Modifier.height(64.dp)) {
      Tab.entries.forEach { t ->
        val on = t == selected
        NavigationBarItem(
          selected = on,
          onClick = { onTab(t) },
          icon = { Icon(if (on) t.selectedIcon else t.icon, contentDescription = null, modifier = Modifier.size(26.dp)) },
          label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
          colors =
            NavigationBarItemDefaults.colors(
              selectedIconColor = MaterialTheme.colorScheme.primary,
              selectedTextColor = MaterialTheme.colorScheme.primary,
              unselectedIconColor = Fp.palette.muted,
              unselectedTextColor = Fp.palette.muted,
              indicatorColor = Color.Transparent,
            ),
          modifier = Modifier.testTag("tab_${t.name}"),
        )
      }
    }
  }
}

/** Gray round icon button for top bars, like a toolbar on a social feed. */
@Composable
fun CircleIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, enabled: Boolean = true) {
  Box(
    Modifier.size(38.dp).background(Fp.palette.chip, CircleShape).clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = contentDescription, tint = Fp.palette.ink, modifier = Modifier.size(22.dp))
  }
}

// ---- Surfaces -------------------------------------------------------------------------------------

/** A white block on the gray canvas. Full width, square edges in lists; [rounded] for inset cards. */
@Composable
fun SectionCard(
  modifier: Modifier = Modifier,
  title: String? = null,
  rounded: Boolean = true,
  padding: PaddingValues = PaddingValues(16.dp),
  onClick: (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val shape = if (rounded) RoundedCornerShape(10.dp) else RoundedCornerShape(0.dp)
  Column(
    modifier.fillMaxWidth().background(Fp.palette.card, shape).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(padding),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = Fp.palette.ink)
    content()
  }
}

/** Small gray heading above a group of cards. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
  Text(text, style = MaterialTheme.typography.titleSmall, color = Fp.palette.muted, modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp))
}

/** Icon in a soft round badge. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, background: Color = Fp.palette.blueTint, size: Dp = 40.dp) {
  Box(Modifier.size(size).background(background, CircleShape), contentAlignment = Alignment.Center) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
  }
}

/** Initials in a circle, used where a profile photo would go. */
@Composable
fun Avatar(name: String?, size: Dp = 40.dp) {
  val initials = name.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "FP" }
  Box(Modifier.size(size).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
    Text(initials, color = MaterialTheme.colorScheme.onPrimary, style = if (size >= 56.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall)
  }
}

/**
 * A tappable settings-style row: icon badge, title, optional subtitle, chevron or a custom trailing slot.
 */
@Composable
fun ListRow(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  icon: ImageVector? = null,
  iconTint: Color? = null,
  iconBackground: Color? = null,
  onClick: (() -> Unit)? = null,
  enabled: Boolean = true,
  trailing: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier.fillMaxWidth().heightIn(min = 56.dp).let { if (onClick != null) it.clickable(enabled = enabled, onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      IconBadge(icon, tint = iconTint ?: Fp.palette.ink, background = iconBackground ?: Fp.palette.chip, size = 36.dp)
      Spacer(Modifier.width(12.dp))
    }
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) Fp.palette.ink else Fp.palette.faint)
      if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted)
    }
    if (trailing != null) {
      Spacer(Modifier.width(8.dp))
      trailing()
    } else if (onClick != null) {
      Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Fp.palette.faint)
    }
  }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, subtitle: String? = null, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
  ListRow(
    title = label,
    subtitle = subtitle,
    icon = icon,
    onClick = { onChange(!checked) },
    trailing = {
      Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, uncheckedTrackColor = Fp.palette.chip, uncheckedBorderColor = Fp.palette.chip, uncheckedThumbColor = Color.White),
      )
    },
  )
}

@Composable
fun RowDivider(inset: Dp = 64.dp) {
  HorizontalDivider(color = Fp.palette.line, modifier = Modifier.padding(start = inset))
}

/** Rounded label: "Today", "Done", "Needs attention". */
@Composable
fun Pill(text: String, color: Color, background: Color, modifier: Modifier = Modifier) {
  Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = modifier.background(background, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
}

enum class BannerKind { INFO, WARN, ERROR, OK }

/** A one-line message strip inside a card or above a form. */
@Composable
fun Banner(text: String, kind: BannerKind = BannerKind.INFO, modifier: Modifier = Modifier) {
  val (icon, fg, bg) =
    when (kind) {
      BannerKind.INFO -> Triple(Icons.Outlined.Info, MaterialTheme.colorScheme.primary, Fp.palette.blueTint)
      BannerKind.WARN -> Triple(Icons.Outlined.WarningAmber, FpColors.Warning, Fp.palette.warningTint)
      BannerKind.ERROR -> Triple(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, Fp.palette.dangerTint)
      BannerKind.OK -> Triple(Icons.Outlined.Info, FpColors.Success, Fp.palette.successTint)
    }
  Row(modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
    Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.padding(top = 1.dp).size(18.dp))
    Spacer(Modifier.width(10.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Fp.palette.ink)
  }
}

@Composable
fun ErrorText(msg: String?) {
  if (!msg.isNullOrBlank()) Banner(msg, BannerKind.ERROR)
}

/** Centered icon, title and one line of help, with an optional action. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
  Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    IconBadge(icon, size = 64.dp)
    Text(title, style = MaterialTheme.typography.titleMedium, color = Fp.palette.ink)
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Fp.palette.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    action?.invoke()
  }
}

// ---- Buttons --------------------------------------------------------------------------------------

private val ButtonShape = RoundedCornerShape(8.dp)

/** The one blue action on a screen. */
@Composable
fun PrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  enabled: Boolean = true,
  loading: Boolean = false,
  tall: Boolean = false,
  color: Color? = null,
) {
  Button(
    onClick = onClick,
    enabled = enabled && !loading,
    shape = ButtonShape,
    colors = ButtonDefaults.buttonColors(containerColor = color ?: MaterialTheme.colorScheme.primary, contentColor = Color.White, disabledContainerColor = Fp.palette.chip, disabledContentColor = Fp.palette.faint),
    elevation = null,
    contentPadding = PaddingValues(horizontal = 16.dp),
    modifier = modifier.fillMaxWidth().height(if (tall) 52.dp else 46.dp),
  ) {
    if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
    else if (icon != null) IconText(icon, text) else Text(text, style = MaterialTheme.typography.labelLarge)
  }
}

/** Gray button for everything that is not the main action. */
@Composable
fun SecondaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  enabled: Boolean = true,
  fill: Boolean = true,
  contentColor: Color? = null,
) {
  Button(
    onClick = onClick,
    enabled = enabled,
    shape = ButtonShape,
    colors = ButtonDefaults.buttonColors(containerColor = Fp.palette.chip, contentColor = contentColor ?: Fp.palette.ink, disabledContainerColor = Fp.palette.chip.copy(alpha = 0.5f), disabledContentColor = Fp.palette.faint),
    elevation = null,
    contentPadding = PaddingValues(horizontal = 14.dp),
    modifier = (if (fill) modifier.fillMaxWidth() else modifier).height(46.dp),
  ) {
    if (icon != null) IconText(icon, text) else Text(text, style = MaterialTheme.typography.labelLarge)
  }
}

/** Blue text link. */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color? = null) {
  TextButton(onClick = onClick, modifier = modifier) { Text(text, style = MaterialTheme.typography.labelLarge, color = color ?: MaterialTheme.colorScheme.primary) }
}

// ---- Inputs ---------------------------------------------------------------------------------------

@Composable
fun FpTextField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  singleLine: Boolean = true,
  minLines: Int = 1,
  supportingText: String? = null,
  placeholder: String? = null,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  visualTransformation: VisualTransformation = VisualTransformation.None,
  leadingIcon: ImageVector? = null,
  readOnly: Boolean = false,
  trailingIcon: (@Composable () -> Unit)? = null,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    placeholder = placeholder?.let { { Text(it, color = Fp.palette.faint) } },
    singleLine = singleLine,
    minLines = minLines,
    readOnly = readOnly,
    supportingText = supportingText?.let { { Text(it, style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted) } },
    keyboardOptions = keyboardOptions,
    visualTransformation = visualTransformation,
    leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null, tint = Fp.palette.muted) } },
    trailingIcon = trailingIcon,
    shape = RoundedCornerShape(8.dp),
    textStyle = MaterialTheme.typography.bodyLarge,
    colors =
      OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        focusedContainerColor = Fp.palette.card,
        unfocusedContainerColor = Fp.palette.card,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = Fp.palette.muted,
        cursorColor = MaterialTheme.colorScheme.primary,
      ),
    modifier = modifier.fillMaxWidth(),
  )
}

/** Horizontal choice chips, e.g. English / Español. */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
  Row(modifier.fillMaxWidth().background(Fp.palette.chip, RoundedCornerShape(8.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    options.forEach { (id, label) ->
      val on = id == selected
      Box(
        Modifier.weight(1f).height(38.dp).background(if (on) Fp.palette.card else Color.Transparent, RoundedCornerShape(6.dp)).clickable { onSelect(id) }.testTag("seg_$id"),
        contentAlignment = Alignment.Center,
      ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.primary else Fp.palette.muted)
      }
    }
  }
}

/** A selectable card used for single choices with an explanation (video source, consent choice). */
@Composable
fun ChoiceCard(title: String, text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
  val border = if (selected) MaterialTheme.colorScheme.primary else Fp.palette.line
  Row(
    modifier.fillMaxWidth().background(if (selected) Fp.palette.blueTint else Fp.palette.card, RoundedCornerShape(10.dp)).border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      IconBadge(icon, tint = if (selected) MaterialTheme.colorScheme.primary else Fp.palette.ink, background = if (selected) Fp.palette.card else Fp.palette.chip, size = 40.dp)
      Spacer(Modifier.width(12.dp))
    }
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall, color = Fp.palette.ink)
      if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted)
    }
    Spacer(Modifier.width(8.dp))
    Box(
      Modifier.size(22.dp).border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape).padding(5.dp).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
    )
  }
}

/** Step progress: filled segments for done and current steps. */
@Composable
fun StepProgress(current: Int, total: Int, modifier: Modifier = Modifier) {
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    repeat(total) { i ->
      Box(Modifier.weight(1f).height(4.dp).background(if (i < current) MaterialTheme.colorScheme.primary else Fp.palette.chip, RoundedCornerShape(2.dp)))
    }
  }
}

/** Two-line label/value block on detail screens. */
@Composable
fun InfoRow(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
  Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    IconBadge(icon, tint = Fp.palette.ink, background = Fp.palette.chip, size = 36.dp)
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.bodySmall, color = Fp.palette.muted)
      Text(value, style = MaterialTheme.typography.bodyLarge, color = Fp.palette.ink)
    }
    trailing?.invoke()
  }
}

/** Equal-width text+icon actions along the bottom of a card (Start visit, Directions, Details). */
@Composable
fun CardActions(content: @Composable RowScope.() -> Unit) {
  Column {
    HorizontalDivider(color = Fp.palette.line)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), content = content)
  }
}

@Composable
fun RowScope.CardAction(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
  Row(
    modifier.weight(1f).height(44.dp).clickable(onClick = onClick),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val c = if (primary) MaterialTheme.colorScheme.primary else Fp.palette.muted
    Icon(icon, contentDescription = null, tint = c, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(6.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = c)
  }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp) }
}

/** Page body that scrolls, with consistent outer padding and spacing between cards. */
@Composable
fun PageColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    content = content,
  )
}

