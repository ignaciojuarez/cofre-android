package com.ignacio.cofre.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// §2 #25 InputHint: controller glyphs, key caps and the hint bar. Hidden from TalkBack.

/** Button faces drawn as shapes rather than font glyphs, so they look the same on every device. */
internal enum class GlyphShape { Cross, Circle, Square, Triangle, Menu, View, Plus, Minus }

/** What a button's face shows: a label or a drawn shape. */
internal data class GlyphFace(val text: String? = null, val shape: GlyphShape? = null)

/** The face of the physical button that does [pad] on a [style] controller. Face buttons are positional. */
internal fun glyphFace(pad: Pad, style: ControllerStyle, swapConfirmBack: Boolean): GlyphFace {
    // Android's A/B/X/Y key codes are south/east/west/north whatever the label says.
    val south = 0
    val east = 1
    val west = 2
    val north = 3
    val position = when (pad) {
        Pad.Confirm -> if (swapConfirmBack) east else south
        Pad.Back -> if (swapConfirmBack) south else east
        Pad.X -> west
        Pad.Y -> north
        else -> -1
    }
    fun t(s: String) = GlyphFace(text = s)
    fun s(shape: GlyphShape) = GlyphFace(shape = shape)
    return when (style) {
        ControllerStyle.Xbox -> when (pad) {
            Pad.L1 -> t("LB")
            Pad.R1 -> t("RB")
            Pad.L2 -> t("LT")
            Pad.R2 -> t("RT")
            Pad.Start -> s(GlyphShape.Menu)
            Pad.Select -> s(GlyphShape.View)
            else -> t(listOf("A", "B", "X", "Y")[position])
        }
        ControllerStyle.PlayStation -> when (pad) {
            Pad.L1 -> t("L1")
            Pad.R1 -> t("R1")
            Pad.L2 -> t("L2")
            Pad.R2 -> t("R2")
            Pad.Start -> s(GlyphShape.Menu)
            Pad.Select -> s(GlyphShape.View)
            else -> s(listOf(GlyphShape.Cross, GlyphShape.Circle, GlyphShape.Square, GlyphShape.Triangle)[position])
        }
        ControllerStyle.Nintendo -> when (pad) {
            Pad.L1 -> t("L")
            Pad.R1 -> t("R")
            Pad.L2 -> t("ZL")
            Pad.R2 -> t("ZR")
            Pad.Start -> s(GlyphShape.Plus)
            Pad.Select -> s(GlyphShape.Minus)
            else -> t(listOf("B", "A", "Y", "X")[position])
        }
    }
}

private val GlyphText = TextStyle(
    fontFamily = CofreType.Geist,
    fontWeight = FontWeight.SemiBold,
    fontSize = 9.5.sp,
    color = Color.White,
)

/** A controller button glyph for the active controller's style. The one rounded shape in Cofre (§0 rule 5). */
@Composable
public fun ControllerGlyph(
    pad: Pad,
    modifier: Modifier = Modifier,
) {
    val controller = LocalController.current
    val face = glyphFace(pad, controller.style, controller.swapConfirmBack)
    Box(
        modifier
            .heightIn(min = 20.dp)
            .widthIn(min = 20.dp)
            .background(hex(PixelArtColors.CONTROLLER_GLYPH), CircleShape)
            .border(2.dp, CofreTheme.colors.outline, CircleShape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (face.text != null) {
            BasicText(face.text, style = GlyphText, maxLines = 1)
        } else {
            Canvas(Modifier.size(9.dp)) { drawGlyph(face.shape ?: GlyphShape.Menu) }
        }
    }
}

private fun DrawScope.drawGlyph(shape: GlyphShape) {
    val w = size.width
    val stroke = Stroke(1.6.dp.toPx())
    val white = Color.White
    when (shape) {
        GlyphShape.Cross -> {
            drawLine(white, Offset(0f, 0f), Offset(w, w), stroke.width)
            drawLine(white, Offset(w, 0f), Offset(0f, w), stroke.width)
        }
        GlyphShape.Circle -> drawCircle(white, w / 2 - stroke.width / 2, style = stroke)
        GlyphShape.Square -> drawRect(white, Offset(stroke.width / 2, stroke.width / 2), Size(w - stroke.width, w - stroke.width), style = stroke)
        GlyphShape.Triangle -> drawPath(
            Path().apply {
                moveTo(w / 2, stroke.width / 2)
                lineTo(w - stroke.width / 2, w - stroke.width / 2)
                lineTo(stroke.width / 2, w - stroke.width / 2)
                close()
            },
            white,
            style = stroke,
        )
        GlyphShape.Menu -> for (i in 0..2) drawRect(white, Offset(0f, w * (0.1f + i * 0.35f)), Size(w, stroke.width))
        GlyphShape.View -> {
            drawRect(white, Offset(0f, w * 0.3f), Size(w * 0.65f, w * 0.65f), style = stroke)
            drawRect(white, Offset(w * 0.35f, 0f), Size(w * 0.65f, w * 0.65f), style = stroke)
        }
        GlyphShape.Plus -> {
            drawRect(white, Offset(0f, (w - stroke.width) / 2), Size(w, stroke.width))
            drawRect(white, Offset((w - stroke.width) / 2, 0f), Size(stroke.width, w))
        }
        GlyphShape.Minus -> drawRect(white, Offset(0f, (w - stroke.width) / 2), Size(w, stroke.width))
    }
}

/**
 * The bottom strip of controller prompts ("Ⓐ Select  Ⓑ Back  LB RB Sections"), for where focus is now. Shown
 * only while the last input came from a controller; touch hides it. Hints keep their order (Select and Back first)
 * and stay on one line: on a narrow screen the trailing ones that don't fit are left out.
 */
@Composable
public fun ControllerHintBar(
    modifier: Modifier = Modifier,
    alwaysShow: Boolean = false,
) {
    val controller = LocalController.current
    if (!alwaysShow && controller.lastInput != InputKind.Controller) return
    val groups = controller.hints.fold(mutableListOf<Pair<MutableList<Pad>, String>>()) { acc, hint ->
        val last = acc.lastOrNull()
        if (last != null && last.second == hint.label) last.first += hint.pad else acc += mutableListOf(hint.pad) to hint.label
        acc
    }
    Layout(
        content = {
            groups.forEach { (pads, label) ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    pads.forEach { ControllerGlyph(it) }
                    CofreText(label, CofreTheme.type.label, Modifier.padding(start = 2.dp), color = CofreTheme.colors.muted, maxLines = 1)
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .block(BlockKind.Well)
            .clearAndSetSemantics {}
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) { measurables, constraints ->
        val gap = 14.dp.roundToPx()
        // Each hint at its natural width; the first that would overflow ends the line.
        val placeables = measurables.map { it.measure(Constraints(maxHeight = constraints.maxHeight)) }
        val shown = placeables.take(hintsThatFit(placeables.map { it.width }, gap, constraints.maxWidth))
        val height = (shown.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        val width = shown.sumOf { it.width } + gap * (shown.size - 1).coerceAtLeast(0)
        layout(if (constraints.hasBoundedWidth) constraints.maxWidth else width, height) {
            var left = 0
            shown.forEach { p ->
                p.placeRelative(left, (height - p.height) / 2)
                left += p.width + gap
            }
        }
    }
}

/** How many hints, in order, fit on one line of [max] px with [gap] px between them. */
internal fun hintsThatFit(widths: List<Int>, gap: Int, max: Int): Int {
    var x = -gap
    return widths.takeWhile { w -> (x + gap + w <= max).also { if (it) x += gap + w } }.size
}
