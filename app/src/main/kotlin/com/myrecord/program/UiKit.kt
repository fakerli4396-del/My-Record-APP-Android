package com.myrecord.program

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.myrecord.domain.amount

internal val Ink = Color(0xFF31251F)
internal val MutedInk = Color(0xFF69584E)
internal val Cream = Color(0xFFFFF9ED)
internal val Paper = Color(0xFFFFFCF5)
internal val Butter = Color(0xFFF7D577)
internal val PaleButter = Color(0xFFFFEEC8)
internal val Pink = Color(0xFFF9DFE5)
internal val RoseInk = Color(0xFFAA3656)
internal val Lilac = Color(0xFFECE2F6)
internal val Mint = Color(0xFFE5EBD3)
internal val Line = Color(0xFFBFAE9E)
internal val NoteShape = RoundedCornerShape(17.dp, 14.dp, 18.dp, 15.dp)
internal val HoverPaper = Color(0xFFFFF1D9)
internal val FocusInk = Color(0xFFA26729)

@Composable
internal fun Note(modifier: Modifier = Modifier, color: Color = Paper, body: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(color, NoteShape).border(1.dp, Ink, NoteShape).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
        content = body
    )
}

@Composable
internal fun Label(text: String) {
    Text(text, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
internal fun Money(value: Long, modifier: Modifier = Modifier, prominent: Boolean = false) {
    Text(
        amount(value), modifier, color = if (prominent || value < 0) RoseInk else Ink,
        fontSize = if (prominent) 28.sp else 16.sp, fontWeight = FontWeight.Bold
    )
}

@Composable
internal fun ValueRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 35.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

@Composable
internal fun Rule() {
    HorizontalDivider(color = Line.copy(alpha = .55f))
}

@Composable
internal fun Primary(label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    LedgerButton(
        label, modifier, shape = RoundedCornerShape(21.dp), color = Butter, onClick = action
    )
}

@Composable
internal fun LedgerButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    color: Color = Paper,
    onClick: () -> Unit
) {
    Pressable(modifier, shape, color, enabled = enabled, onClick = onClick) {
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
    }
}

@Composable
internal fun Pressable(
    modifier: Modifier = Modifier,
    shape: Shape = NoteShape,
    color: Color = Paper,
    active: Boolean = false,
    enabled: Boolean = true,
    role: Role = Role.Button,
    interactions: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val fill by animateColorAsState(
        when {
            !enabled -> Cream
            pressed -> PaleButter
            active -> color
            hovered || focused -> HoverPaper
            else -> color
        }, tween(150), label = "Control surface"
    )
    val stroke by animateColorAsState(
        when {
            focused -> FocusInk
            active || pressed -> RoseInk
            hovered -> FocusInk
            else -> Line
        }, tween(150), label = "Control outline"
    )
    val outline by animateDpAsState(if (focused) 2.dp else 1.dp, tween(150), label = "Focus outline")
    val shift by animateDpAsState(
        when {
            pressed -> 1.dp
            hovered -> (-1).dp
            else -> 0.dp
        }, tween(150), label = "Press offset"
    )
    Box(
        modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(shape).background(fill).border(outline, stroke, shape)
            .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = role, onClick = onClick)
            .then(if (role == Role.Tab) Modifier.semantics { selected = active } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.offset(y = shift), contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
internal fun TabStrip(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Pink, CircleShape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { option ->
            val active = option == selected
            Pressable(
                active = active, onClick = { onSelect(option) }, modifier = Modifier.weight(1f),
                color = if (active) Butter else Paper, shape = CircleShape, role = Role.Tab
            ) {
                Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    Text(option, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
internal fun PaperRow(label: String, glyph: Glyph, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 51.dp)) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
            if (leading == null) Sticker(glyph, PaleButter) else leading()
            Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text("›", color = MutedInk, fontSize = 23.sp)
        }
    }
}

internal enum class Glyph { Home, Book, Chart, Wallet, More, Food, Train, Bag, Folder }

@Composable
internal fun Sticker(glyph: Glyph, fill: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(34.dp).background(fill, RoundedCornerShape(11.dp)).padding(5.dp)) {
        val u = size.width / 24f
        fun point(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x: Float, y: Float, a: Float, b: Float) =
            drawLine(Ink, point(x, y), point(a, b), 1.55f * u, StrokeCap.Round)
        fun outline(vararg xy: Float) {
            val path = Path().apply {
                moveTo(xy[0] * u, xy[1] * u)
                for (i in 2 until xy.size step 2) lineTo(xy[i] * u, xy[i + 1] * u)
            }
            drawPath(path, Ink, style = Stroke(1.55f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        fun rect(x: Float, y: Float, w: Float, h: Float) =
            drawRoundRect(Ink, topLeft = point(x, y), size = androidx.compose.ui.geometry.Size(w * u, h * u),
                cornerRadius = CornerRadius(2f * u), style = Stroke(1.55f * u))
        when (glyph) {
            Glyph.Home -> {
                outline(2f, 11f, 12f, 3f, 22f, 11f)
                outline(5f, 10f, 5f, 21f, 10f, 21f, 10f, 15f, 14f, 15f, 14f, 21f, 19f, 21f, 19f, 10f)
            }
            Glyph.Book -> {
                rect(5f, 3f, 14f, 18f)
                line(8f, 8f, 16f, 8f)
                line(8f, 12f, 16f, 12f)
                line(8f, 16f, 14f, 16f)
            }
            Glyph.Chart -> {
                outline(4f, 5f, 4f, 20f, 21f, 20f)
                line(8f, 16f, 8f, 12f)
                line(13f, 16f, 13f, 8f)
                line(18f, 16f, 18f, 4f)
            }
            Glyph.Wallet -> {
                rect(3f, 6f, 18f, 14f)
                rect(14f, 11f, 7f, 5f)
                drawCircle(Ink, u * .8f, point(17f, 13.5f))
            }
            Glyph.More -> (6..18 step 6).forEach { drawCircle(Ink, u * 1.45f, point(it.toFloat(), 12f)) }
            Glyph.Food -> {
                outline(3f, 13f, 7f, 19f, 17f, 19f, 21f, 13f, 3f, 13f)
                line(9f, 10f, 11f, 5f)
                line(14f, 10f, 16f, 5f)
            }
            Glyph.Train -> {
                rect(5f, 3f, 14f, 15f)
                line(5f, 11f, 19f, 11f)
                line(8f, 18f, 5f, 21f)
                line(16f, 18f, 19f, 21f)
            }
            Glyph.Bag -> {
                outline(6f, 8f, 18f, 8f, 21f, 21f, 3f, 21f, 6f, 8f)
                outline(9f, 9f, 9f, 5f, 12f, 3f, 15f, 5f, 15f, 9f)
            }
            Glyph.Folder -> outline(3f, 4f, 10f, 4f, 12f, 7f, 21f, 7f, 21f, 20f, 3f, 20f, 3f, 4f)
        }
    }
}

@Composable
internal fun DatedHeader(title: String, back: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        if (back != null) LedgerButton("返回", onClick = back)
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun SelectField(value: String, options: List<String>, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    LedgerSelect(value, options.map { it to it }, value, modifier, onSelect)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun <T> LedgerSelect(
    value: T, options: List<Pair<T, String>>, placeholder: String,
    modifier: Modifier = Modifier, onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val fieldDp = with(LocalDensity.current) { fieldWidth.toDp() }
    val screen = windowWidth().value
    val useSheet = screen < 344 || (options.size > 6 && fieldDp < 140.dp)
    val close = {
        expanded = false
        focus.requestFocus()
        Unit
    }
    val pick: (T) -> Unit = {
        close()
        onSelect(it)
    }
    Box(modifier) {
        Pressable(
            Modifier.fillMaxWidth().focusRequester(focus).onSizeChanged { fieldWidth = it.width }
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
            enabled = options.isNotEmpty(), active = expanded,
            onClick = { expanded = true }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                Arrangement.spacedBy(8.dp), Alignment.CenterVertically
            ) {
                Text(options.firstOrNull { it.first == value }?.second ?: placeholder, Modifier.weight(1f))
                Text(if (expanded) "▴" else "▾", color = MutedInk)
            }
        }
        if (!useSheet) {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme.copy(surface = Paper, surfaceContainer = Paper),
                shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(16.dp))
            ) {
                DropdownMenu(
                    expanded = expanded, onDismissRequest = close,
                    modifier = Modifier.widthIn(min = maxOf(132.dp, fieldDp), max = (screen - 16).coerceAtLeast(132f).dp)
                ) {
                    options.forEach { (key, label) -> MenuOption(label, key == value) { pick(key) } }
                }
            }
        } else if (expanded) {
            ModalBottomSheet(onDismissRequest = close, containerColor = Paper) {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                    options.forEach { (key, label) -> MenuOption(label, key == value) { pick(key) } }
                }
            }
        }
    }
}

@Composable
internal fun windowWidth(): Dp {
    val window = LocalView.current.rootView
    var width by remember(window) { mutableIntStateOf(window.width) }
    DisposableEffect(window) {
        val listener = android.view.View.OnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            width = right - left
        }
        window.addOnLayoutChangeListener(listener)
        onDispose { window.removeOnLayoutChangeListener(listener) }
    }
    return with(LocalDensity.current) {
        (if (width > 0) width else window.resources.displayMetrics.widthPixels).toDp()
    }
}

@Composable
private fun MenuOption(option: String, active: Boolean, onSelect: () -> Unit) {
    Pressable(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { selected = active },
        color = if (active) Pink else Paper,
        active = active, onClick = onSelect
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), Arrangement.spacedBy(9.dp)) {
            Text(option, Modifier.weight(1f))
            if (active) Text("✓", color = RoseInk)
        }
    }
}
