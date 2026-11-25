package com.myrecord.program

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myrecord.domain.amount
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal val IncomeChartInk = Color(0xFF3F6956)
internal val ExpenseChartInk = Color(0xFFAD3457)
internal val RingPalette = listOf(
    Color(0xFFD67C76), Color(0xFFF0CD76), Color(0xFF9ACCB7), Color(0xFFE5A582),
    Color(0xFFE9B8CC), Color(0xFFA69BD6), Color(0xFF84BCC4)
)

internal data class RingSlice(val label: String, val cents: Long, val color: Color)

private data class RingArc(val slice: RingSlice, val start: Float, val sweep: Float, val gap: Float) {
    val middle get() = start + sweep / 2
}

@Composable
internal fun LedgerRingChart(
    slices: List<RingSlice>, total: Long, totalLabel: String, open: (Int) -> Unit
) {
    if (slices.isEmpty() || total <= 0) return
    val arcs = remember(slices, total) {
        var start = -90f
        slices.map { slice ->
            val sweep = fraction(slice.cents, total) * 360f
            val gap = if (slices.size == 1) 0f else minOf(2f, sweep * .24f)
            RingArc(slice, start, sweep, gap).also { start += sweep }
        }
    }
    var pointed by remember(arcs) { mutableStateOf<Int?>(null) }
    var legendActive by remember(arcs) { mutableStateOf<Int?>(null) }
    val active = pointed ?: legendActive
    val latestOpen by rememberUpdatedState(open)
    val progress = arcs.indices.map { index ->
        animateFloatAsState(if (index == active) 1f else 0f, tween(180), label = "环形图扇区")
    }
    val textMeasurer = rememberTextMeasurer()
    val highlighted = active?.let(slices::get)
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val centerWidth = minOf(maxWidth * .27f, 110.dp)
        Canvas(
            Modifier.fillMaxWidth().height(228.dp).testTag("ledger-ring")
                .pointerInput(arcs) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Exit -> pointed = null
                                PointerEventType.Enter, PointerEventType.Move -> pointed =
                                    event.changes.firstOrNull()?.position?.let {
                                        ringHit(it, Size(size.width.toFloat(), size.height.toFloat()), arcs, pointed)
                                    }
                            }
                        }
                    }
                }.pointerInput(arcs) {
                    detectTapGestures { point ->
                        ringHit(point, Size(size.width.toFloat(), size.height.toFloat()), arcs, pointed)
                            ?.let(latestOpen)
                    }
                }
        ) {
            val radius = ringRadius(size)
            val inner = radius * .55f
            for (index in arcs.indices) {
                val arc = arcs[index]
                val outer = radius * (1f + .14f * progress[index].value)
                val middle = (inner + outer) / 2f
                val diameter = middle * 2
                drawArc(
                    arc.slice.color, arc.start + arc.gap / 2, arc.sweep - arc.gap, useCenter = false,
                    topLeft = center - Offset(middle, middle), size = Size(diameter, diameter),
                    style = Stroke(outer - inner)
                )
            }
            drawRingCallouts(arcs, total, radius, textMeasurer)
        }
        Column(Modifier.width(centerWidth), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(highlighted?.label ?: totalLabel, color = MutedInk, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                highlighted?.let { percent(fraction(it.cents, total)) } ?: amount(total),
                color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
    slices.forEachIndexed { index, slice ->
        val source = remember(slice) { MutableInteractionSource() }
        val hovered by source.collectIsHoveredAsState()
        val focused by source.collectIsFocusedAsState()
        LaunchedEffect(hovered, focused, index) {
            if (hovered || focused) legendActive = index else if (legendActive == index) legendActive = null
        }
        Pressable(Modifier.fillMaxWidth(), active = active == index, interactions = source,
            color = if (active == index) Pink else Paper, onClick = { open(index) }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(slice.color, CircleShape))
                Text(slice.label, Modifier.weight(1f))
                Text(percent(fraction(slice.cents, total)), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun ringRadius(size: Size) = minOf(size.width * .265f, size.height * .40f)

private fun ringHit(point: Offset, size: Size, arcs: List<RingArc>, active: Int?): Int? {
    val delta = point - Offset(size.width / 2, size.height / 2)
    val radius = ringRadius(size)
    val distance = delta.getDistance()
    if (distance < radius * .55f || distance > radius * 1.14f) return null
    val angle = (Math.toDegrees(atan2(delta.y.toDouble(), delta.x.toDouble())) + 450.0) % 360.0 - 90
    return arcs.indices.firstOrNull { index ->
        val arc = arcs[index]
        angle >= arc.start + arc.gap / 2 && angle <= arc.start + arc.sweep - arc.gap / 2 &&
            (distance <= radius || index == active)
    }
}

private fun DrawScope.drawRingCallouts(arcs: List<RingArc>, total: Long, radius: Float, measurer: TextMeasurer) {
    val edge = 4.dp.toPx()
    val gutter = center.x - radius * 1.14f - 6.dp.toPx()
    if (gutter < 38.dp.toPx()) return
    val anchorRadius = radius * 1.17f
    val bySide = arcs.take(if (radius < 68.dp.toPx()) 2 else 3).groupBy { cos(Math.toRadians(it.middle.toDouble())) >= 0.0 }
    bySide.forEach { (right, entries) ->
        val sorted = entries.sortedBy { sin(Math.toRadians(it.middle.toDouble())) }
        val spacing = 42.dp.toPx()
        var previous = Float.NEGATIVE_INFINITY
        sorted.forEachIndexed { index, arc ->
            val theta = Math.toRadians(arc.middle.toDouble())
            val anchor = center + Offset(cos(theta).toFloat(), sin(theta).toFloat()) * anchorRadius
            val y = maxOf(28.dp.toPx(), previous + spacing, anchor.y)
                .coerceAtMost(size.height - 15.dp.toPx() - (sorted.lastIndex - index) * spacing)
            previous = y
            val elbow = Offset(if (right) size.width - gutter else gutter, y)
            val end = Offset(if (right) size.width - edge else edge, y)
            drawCircle(arc.slice.color, 2.5.dp.toPx(), anchor)
            drawLine(arc.slice.color, anchor, elbow, 1.dp.toPx())
            drawLine(arc.slice.color, elbow, end, 1.dp.toPx())
            val available = Constraints(maxWidth = (gutter - edge).toInt().coerceAtLeast(1))
            val name = measurer.measure(arc.slice.label, TextStyle(color = MutedInk, fontSize = 10.sp),
                overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = available)
            val share = measurer.measure(percent(fraction(arc.slice.cents, total)),
                TextStyle(color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                maxLines = 1, constraints = available)
            val x = if (right) size.width - gutter else edge
            val textBottom = y - 3.dp.toPx()
            drawText(name, topLeft = Offset(x, textBottom - share.size.height - name.size.height))
            drawText(share, topLeft = Offset(x, textBottom - share.size.height))
        }
    }
}

@Composable
internal fun ChartAmountRow(
    label: String, cents: Long, maximum: Long, color: Color, modifier: Modifier = Modifier,
    caption: String? = null, leading: (@Composable () -> Unit)? = null, open: (() -> Unit)? = null
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    var pinned by rememberSaveable { mutableStateOf(false) }
    val highlighted = hovered || focused || pinned
    val thickness by animateDpAsState(if (highlighted) 11.dp else 6.dp, tween(180), label = "收支条高度")
    val scale by animateFloatAsState(if (highlighted) 1.09f else 1f, tween(180), label = "收支金额字号")
    val tint = if (highlighted) color else Ink
    Pressable(
        modifier.fillMaxWidth().semantics { if (highlighted) stateDescription = "已高亮" },
        color = if (highlighted) color.copy(alpha = .13f) else Paper, active = highlighted,
        interactions = source, onClick = open ?: { pinned = !pinned }
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            Arrangement.spacedBy(9.dp), Alignment.CenterVertically) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f).graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, .5f)
                    }, color = tint, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(amount(cents), Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(1f, .5f)
                    }, color = tint, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.fillMaxWidth().height(13.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height(thickness).background(color.copy(alpha = .12f), CircleShape))
                    Box(Modifier.fillMaxWidth(fraction(cents, maximum)).height(thickness).background(color, CircleShape))
                }
                caption?.let { Label(it) }
            }
        }
    }
}
