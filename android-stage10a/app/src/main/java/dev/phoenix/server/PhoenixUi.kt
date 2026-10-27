package dev.phoenix.server

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Phoenix's colors: the red/black brand on near-black surfaces. Status always uses the same four: green ok, blue working,
 * amber attention, red error (errors also carry an icon or label, so they read apart from the red brand accent).
 */
object Palette {
    val Bg = Color(0xFF0A0A0B)
    val Card = Color(0xFF151517)
    val CardHigh = Color(0xFF1E1E21)
    val Line = Color(0xFF2B2B2F)
    /** Brand red; 6:1 on Bg. Filled brand buttons use near-black OnBrand text (6:1), not white (3:1). */
    val Brand = Color(0xFFFF4D47)
    val OnBrand = Color(0xFF160406)
    val Good = Color(0xFF4ADE80)
    val Warn = Color(0xFFFBBF24)
    val Bad = Color(0xFFF87171)
    val Info = Color(0xFF60A5FA)
    val Text = Color(0xFFF3F4F6)
    val Muted = Color(0xFF9CA3AF)
    val Faint = Color(0xFF6B7280)
    val Console = Color(0xFF0A0C0E)
}

enum class Tone { OK, WORKING, WARN, ERROR, IDLE }

val Tone.color: Color get() = when (this) {
    Tone.OK -> Palette.Good
    Tone.WORKING -> Palette.Info
    Tone.WARN -> Palette.Warn
    Tone.ERROR -> Palette.Bad
    Tone.IDLE -> Palette.Muted
}

/**
 * Touch feedback without animation: a flat highlight only while a finger is down. Replaces Material's
 * ripple, which animated on every tap and made switching tabs feel slow.
 */
private object PressHighlight : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0x5048
    private class Node(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private var presses = 0
        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect {
                    presses = when (it) {
                        is PressInteraction.Press -> presses + 1
                        is PressInteraction.Release, is PressInteraction.Cancel -> (presses - 1).coerceAtLeast(0)
                        else -> return@collect
                    }
                    invalidateDraw()
                }
            }
        }
        override fun ContentDrawScope.draw() {
            drawContent()
            if (presses > 0) drawRect(Color.White.copy(alpha = 0.07f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PhoenixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Brand, onPrimary = Palette.OnBrand,
            secondary = Palette.Brand, onSecondary = Palette.OnBrand,
            secondaryContainer = Palette.CardHigh, onSecondaryContainer = Palette.Brand,
            background = Palette.Bg, onBackground = Palette.Text,
            surface = Palette.Card, onSurface = Palette.Text,
            surfaceVariant = Palette.CardHigh, onSurfaceVariant = Palette.Muted,
            surfaceContainer = Palette.Card, surfaceContainerHigh = Palette.CardHigh,
            surfaceContainerHighest = Palette.CardHigh, surfaceContainerLow = Palette.Card,
            outline = Palette.Line, outlineVariant = Palette.Line,
            error = Palette.Bad, onError = Palette.Bg,
        ),
    ) {
        // Text without its own color reads as light on every dark surface, dialogs included. No ripples anywhere.
        CompositionLocalProvider(
            LocalContentColor provides Palette.Text,
            LocalIndication provides PressHighlight,
            LocalRippleConfiguration provides null,
            content = content,
        )
    }
}

/** "PHOENIX SERVER" in the app's two colors. */
@Composable fun BrandTitle(modifier: Modifier = Modifier, size: TextUnit = 20.sp, shadow: Boolean = false) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Palette.Text)) { append("PHOENIX ") }
            withStyle(SpanStyle(color = Palette.Brand)) { append("SERVER") }
        },
        modifier = modifier, fontSize = size, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp,
        style = if (shadow) TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 3f), 12f)) else TextStyle.Default,
    )
}

/** The brand bar at the top of every tab except Home. */
/** The Minecraft artwork, decoded once per process off the main thread; decoding it on every tab switch made switching feel slow. */
object HeroArt {
    @Volatile private var bitmap: ImageBitmap? = null
    fun peek(): ImageBitmap? = bitmap
    fun load(resources: android.content.res.Resources): ImageBitmap = bitmap ?: synchronized(this) {
        bitmap ?: android.graphics.BitmapFactory.decodeResource(resources, R.drawable.hero_minecraft).asImageBitmap()
            .also { it.prepareToDraw(); bitmap = it }
    }
}

@Composable fun HeroArtImage(modifier: Modifier, colorFilter: ColorFilter? = null) {
    val resources = LocalContext.current.resources
    val art by produceState(HeroArt.peek()) { if (value == null) value = withContext(Dispatchers.Default) { HeroArt.load(resources) } }
    art?.let { Image(it, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop, colorFilter = colorFilter) }
        ?: Box(modifier.background(Palette.CardHigh))
}

@Composable fun TopBrandBar() {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_phoenix), contentDescription = null, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(8.dp))
        BrandTitle(size = 22.sp)
    }
}

/** A tab's title: an icon and a large name, with an optional action on the right. */
@Composable fun ScreenHeader(title: String, icon: ImageVector, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(26.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(start = 10.dp))
        trailing?.invoke(this)
    }
}

/** The app's card. */
@Composable fun Panel(modifier: Modifier = Modifier, padding: Dp = 16.dp, spacing: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Palette.Card, border = BorderStroke(1.dp, Palette.Line)) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

/** A card inside a card, one shade lighter. */
@Composable fun InnerPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Palette.CardHigh, contentColor = Palette.Text) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable fun PanelHeader(
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Muted) }
        }
        trailing?.invoke(this)
    }
}

@Composable fun IconTile(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.56f))
    }
}

/** A multi-colored drawable (Java cup, grass block) on a dark tile. */
@Composable fun ArtTile(drawable: Int, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Palette.CardHigh), contentAlignment = Alignment.Center) {
        Image(painterResource(drawable), contentDescription = null, modifier = Modifier.size(size * 0.66f))
    }
}

private val buttonShape = RoundedCornerShape(14.dp)

/** The red brand main action. While [loading] it shows a spinner and ignores taps. */
@Composable fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick, enabled = enabled && !loading, shape = buttonShape, modifier = modifier.heightIn(min = 50.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Brand, contentColor = Palette.OnBrand,
            disabledContainerColor = if (loading) Palette.Brand.copy(alpha = 0.22f) else Palette.CardHigh,
            disabledContentColor = if (loading) Palette.Brand else Palette.Faint,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Brand)
        else if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        if (loading || icon != null) Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A dark action next to a primary one. */
@Composable fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentColor: Color = Palette.Text,
) {
    Button(
        onClick = onClick, enabled = enabled, shape = buttonShape, modifier = modifier.heightIn(min = 50.dp),
        border = BorderStroke(1.dp, Palette.Line),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.CardHigh, contentColor = contentColor,
            disabledContainerColor = Palette.Card, disabledContentColor = Palette.Faint,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        if (icon != null) { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A 40 dp button for rows of actions, brand red when [primary]. */
@Composable fun CompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(10.dp), modifier = modifier.height(40.dp),
        border = if (primary) null else BorderStroke(1.dp, Palette.Line),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Palette.Brand else Palette.CardHigh, contentColor = if (primary) Palette.OnBrand else Palette.Text,
            disabledContainerColor = if (primary) Palette.Brand.copy(alpha = 0.22f) else Palette.Card, disabledContentColor = Palette.Faint,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        if (icon != null) { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A whole-number field (coordinates, radius). */
@Composable fun NumberField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedTextField(
        value, { text -> onValue(text.filterIndexed { i, c -> c.isDigit() || (i == 0 && c == '-') }.take(9)) },
        label = { Text(label, maxLines = 1) }, singleLine = true, enabled = enabled, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = phoenixFieldColors(), shape = RoundedCornerShape(12.dp),
    )
}

/** A small rounded action used in card headers, such as "+ Add mod". */
@Composable fun SmallAction(text: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.CardHigh).border(1.dp, Palette.Line, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp).alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** A brand-red-or-dark pill for filters and option choices. */
@Composable fun ChoicePill(text: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        color = when { !enabled -> Palette.Faint; selected -> Palette.OnBrand; else -> Palette.Text },
        modifier = Modifier.clip(CircleShape).background(if (selected && enabled) Palette.Brand else Palette.CardHigh)
            .border(1.dp, if (selected && enabled) Palette.Brand else Palette.Line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable fun phoenixSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Palette.OnBrand, checkedTrackColor = Palette.Brand, checkedBorderColor = Palette.Brand,
    uncheckedThumbColor = Palette.Muted, uncheckedTrackColor = Palette.CardHigh, uncheckedBorderColor = Palette.Line,
    disabledCheckedTrackColor = Palette.Brand.copy(alpha = 0.35f), disabledCheckedThumbColor = Palette.OnBrand,
    disabledUncheckedTrackColor = Palette.Card, disabledUncheckedThumbColor = Palette.Faint,
)

@Composable fun phoenixFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Brand, unfocusedBorderColor = Palette.Line,
    focusedLabelColor = Palette.Brand, cursorColor = Palette.Brand,
    focusedContainerColor = Palette.Bg, unfocusedContainerColor = Palette.Bg, disabledContainerColor = Palette.Card,
)

/** A colored dot. It doesn't animate: an endless pulse redrew the screen every frame and took CPU from the server. */
@Composable fun StatusDot(tone: Tone, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(tone.color))
}

/** A dot and a word, such as "● Connected". */
@Composable fun StatusText(text: String, tone: Tone, modifier: Modifier = Modifier, bold: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(tone, 8.dp)
        Text(text, color = if (tone == Tone.IDLE) Palette.Muted else tone.color, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.padding(start = 6.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable fun StatusPill(text: String, tone: Tone) {
    Row(
        Modifier.clip(CircleShape).background(tone.color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(tone, 8.dp)
        Text(text, color = tone.color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** A small rounded label, for example "Required" or "Active". */
@Composable fun Tag(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** A one-line message in the tone's color: red for errors, amber for attention, green for done, blue for progress. */
@Composable fun Banner(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(Palette.Card).background(tone.color.copy(alpha = 0.12f))
            .border(1.dp, tone.color.copy(alpha = 0.35f), shape).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tone == Tone.WORKING) CircularProgressIndicator(Modifier.size(18.dp), color = tone.color, strokeWidth = 2.dp)
        else Icon(
            when (tone) {
                Tone.ERROR -> Icons.Rounded.ErrorOutline
                Tone.WARN -> Icons.Rounded.WarningAmber
                Tone.OK -> Icons.Rounded.CheckCircle
                else -> Icons.Rounded.Info
            }, contentDescription = null, tint = tone.color, modifier = Modifier.size(20.dp),
        )
        Text(text, color = if (tone == Tone.ERROR) Palette.Bad else Palette.Text, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp))
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel, color = tone.color, fontWeight = FontWeight.Bold) }
        if (onDismiss != null) IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = Palette.Muted, modifier = Modifier.size(18.dp))
        }
    }
}

/** One numbered setup step: a check when done, a spinner while running, "!" when it needs the user. */
@Composable fun StepRow(number: Int, title: String, detail: String?, tone: Tone) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(
                when (tone) {
                    Tone.OK -> Palette.Good.copy(alpha = 0.18f)
                    Tone.ERROR -> Palette.Bad.copy(alpha = 0.18f)
                    Tone.WARN -> Palette.Warn.copy(alpha = 0.18f)
                    else -> Palette.Bg
                }),
            contentAlignment = Alignment.Center,
        ) {
            when (tone) {
                Tone.OK -> Icon(Icons.Rounded.Check, contentDescription = "Done", tint = Palette.Good, modifier = Modifier.size(16.dp))
                Tone.WORKING -> CircularProgressIndicator(Modifier.size(15.dp), color = Palette.Info, strokeWidth = 2.dp)
                Tone.ERROR, Tone.WARN -> Icon(Icons.Rounded.PriorityHigh, contentDescription = "Needs attention", tint = tone.color, modifier = Modifier.size(16.dp))
                Tone.IDLE -> Text("$number", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                color = if (tone == Tone.IDLE) Palette.Muted else Palette.Text)
            if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = when (tone) { Tone.ERROR -> Palette.Bad; Tone.WARN -> Palette.Warn; Tone.WORKING -> Palette.Info; else -> Palette.Muted })
        }
    }
}

/** One line of text that shrinks to fit instead of wrapping, for addresses friends have to type exactly. */
@Composable fun FitText(text: String, color: Color, modifier: Modifier = Modifier, maxSize: TextUnit = 16.sp, minSize: TextUnit = 11.sp, bold: Boolean = true) {
    BasicText(
        text, modifier, maxLines = 1,
        style = TextStyle(color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontSize = maxSize),
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = maxSize, stepSize = 0.5.sp),
    )
}

/** A thin rounded progress bar. */
@Composable fun Meter(fraction: Float, modifier: Modifier = Modifier, color: Color = Palette.Brand) {
    Box(modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Palette.CardHigh)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(color))
    }
}

/** One dashboard number: a label, a big value, an icon and an optional meter. */
@Composable fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    unit: String? = null,
    tone: Tone = Tone.IDLE,
    meter: Float? = null,
    iconTint: Color = Palette.Brand,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).background(Palette.Card).border(1.dp, Palette.Line, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.weight(1f), maxLines = 1)
            if (icon != null) Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                color = if (tone == Tone.IDLE) Palette.Text else tone.color)
            if (unit != null) Text(" $unit", style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
        }
        if (meter != null) Meter(meter)
    }
}

/** A label on the left, a value on the right; for compact detail lists. */
@Composable fun InfoRow(label: String, value: String, valueColor: Color = Palette.Text, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    }
}

/** A tappable settings row: label and current value, with a chevron or a custom trailing control. */
@Composable fun SettingRow(
    label: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled && onClick != null) { onClick?.invoke() }
            .padding(vertical = 10.dp, horizontal = 4.dp).alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) trailing()
        else if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Palette.Muted)
    }
}

/** A Yes/No dialog; the confirm label turns red for actions that remove or replace something. */
@Composable fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.CardHigh,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(body, color = Palette.Muted) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel, color = if (destructive) Palette.Bad else Palette.Brand, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.Muted) } },
    )
}

private val skins = listOf(Color(0xFFE0AC7E), Color(0xFFC68B59), Color(0xFF8D5A3B), Color(0xFFF1C9A5), Color(0xFF6B4226))
private val hairs = listOf(Color(0xFF3B2416), Color(0xFF1F1F1F), Color(0xFFD9A441), Color(0xFF8C3B1F), Color(0xFF5E3A20), Color(0xFFB0B0B0))
private val eyes = listOf(Color(0xFF3D6FD1), Color(0xFF5A3A22), Color(0xFF2F8F4E), Color(0xFF6B4BA8))

/** An 8×8 pixel face made from the player's name, so each player has a recognizable avatar without any network request. */
@Composable fun PixelAvatar(name: String, size: Dp = 38.dp) {
    val seed = remember(name) { abs(name.lowercase().hashCode()) }
    Canvas(Modifier.size(size).clip(RoundedCornerShape(8.dp))) {
        val p = this.size.width / 8f
        fun px(x: Int, y: Int, color: Color, w: Int = 1) = drawRect(color, Offset(x * p, y * p), Size(w * p + 0.5f, p + 0.5f))
        val skin = skins[seed % skins.size]
        val hair = hairs[(seed / 7) % hairs.size]
        val eye = eyes[(seed / 49) % eyes.size]
        drawRect(skin)
        px(0, 0, hair, 8); px(0, 1, hair, 8)
        px(0, 2, hair); px(7, 2, hair)
        if (seed % 3 == 0) { px(1, 2, hair, 2); px(5, 2, hair) }
        if (seed % 4 == 1) { px(0, 3, hair); px(7, 3, hair) }
        px(1, 4, Color.White); px(2, 4, eye); px(5, 4, eye); px(6, 4, Color.White)
        px(3, 5, skin.copy(red = skin.red * 0.82f, green = skin.green * 0.8f, blue = skin.blue * 0.8f), 2)
        px(2, 6, Color(0xFF4A2A1A), 4)
        if (seed % 5 == 2) px(2, 7, hair, 4)
    }
}

/** Picks a color for a status message from its wording: errors read red, waiting amber, progress blue and success green. */
fun toneOf(message: String): Tone {
    val m = message.lowercase()
    return when {
        listOf("couldn't", "could not", "failed", "taken", "not available", "unavailable", "invalid", "stopped", "error", "doesn't", "does not",
            "mismatch", "no cname", "needs port", "not owned").any { it in m } -> Tone.ERROR
        listOf("sign in", "expired", "wait", "not checked", "not linked", "isn't").any { it in m } -> Tone.WARN
        m.endsWith("…") || m.endsWith("...") -> Tone.WORKING
        listOf("available", "saved", "verified", "points to", "found", "signed in", "is yours", "ready").any { it in m } -> Tone.OK
        else -> Tone.IDLE
    }
}

/** Copies to the clipboard; Android 13+ shows its own confirmation, older versions get a toast. */
fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

fun gb(bytes: Long) = if (bytes >= 1_073_741_824L) "%.1f GB".format(bytes / 1_073_741_824.0) else "${bytes / 1_048_576} MB"

/** "12m", "1h 26m" or "3d 4h". */
fun shortElapsed(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 60 -> "${m}m"
        m < 24 * 60 -> "${m / 60}h ${m % 60}m"
        else -> "${m / 1440}d ${(m % 1440) / 60}h"
    }
}

/** "just now", "5m ago", "2h ago" or "3d ago". */
fun ago(epochMs: Long): String {
    val ms = (System.currentTimeMillis() - epochMs).coerceAtLeast(0)
    return if (ms < 60_000) "just now" else "${shortElapsed(ms).substringBefore(' ')} ago"
}
