package com.ignacio.cofre.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

// §2 #1 Block and §1.4 geometry: 2 dp outline, bevel on top, lip below, square.

/** What a block is made of. */
@Immutable
public sealed interface BlockKind {
    public data object Panel : BlockKind
    public data object Raised : BlockKind
    public data object Well : BlockKind
    public data object Button : BlockKind
    public data object Primary : BlockKind
    public data class Tinted(val tone: Tone) : BlockKind
}

/**
 * Draws a block behind the content: fill, top bevel, bottom [lip], a 4 dp accent bar on the leading edge when
 * [selected], and the 2 dp outline. [flat] drops bevel and lip (disabled controls).
 */
public fun Modifier.block(
    kind: BlockKind = BlockKind.Panel,
    lip: Dp = CofreBlock.lip,
    selected: Boolean = false,
    flat: Boolean = false,
): Modifier = this then BlockElement(kind, lip, selected, flat)

/** A box with a block behind its content. Container only; it's not focusable. */
@Composable
public fun Block(
    modifier: Modifier = Modifier,
    kind: BlockKind = BlockKind.Panel,
    lip: Dp = CofreBlock.lip,
    selected: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.block(kind, lip, selected), content = content)
}

private data class BlockElement(val kind: BlockKind, val lip: Dp, val selected: Boolean, val flat: Boolean) :
    ModifierNodeElement<BlockNode>() {
    override fun create() = BlockNode(kind, lip, selected, flat)
    override fun update(node: BlockNode) {
        node.kind = kind
        node.lip = lip
        node.selected = selected
        node.flat = flat
        node.invalidateDraw()
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "block"
        properties["kind"] = kind
        properties["selected"] = selected
    }
}

private class BlockNode(var kind: BlockKind, var lip: Dp, var selected: Boolean, var flat: Boolean) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    override fun ContentDrawScope.draw() {
        drawBlock(currentValueOf(LocalCofreColors), kind, lip, selected, flat)
        drawContent()
    }
}

internal fun DrawScope.drawBlock(c: CofreColors, kind: BlockKind, lip: Dp, selected: Boolean, flat: Boolean) {
    val (fill, bevel, shade) = when (kind) {
        BlockKind.Panel -> Triple(if (selected) c.raised else c.panel, c.bevel, c.lip)
        BlockKind.Raised -> Triple(c.raised, c.bevel, c.lip)
        BlockKind.Well -> Triple(c.well, null, null)
        BlockKind.Button -> Triple(c.button, c.buttonBevel, c.lip)
        BlockKind.Primary -> Triple(c.face, c.faceBevel, c.faceLip)
        is BlockKind.Tinted -> Triple(c.tint(kind.tone), c.bevel, c.lip)
    }
    val o = CofreBlock.outline.toPx()
    drawRect(fill)
    if (!flat && bevel != null) drawRect(bevel, Offset(o, o), Size(size.width - 2 * o, CofreBlock.bevel.toPx()))
    val lipPx = lip.toPx()
    if (!flat && shade != null && lipPx > 0) {
        drawRect(shade, Offset(o, size.height - o - lipPx), Size(size.width - 2 * o, lipPx))
    }
    if (selected) {
        val bar = CofreBlock.selectionBar.toPx()
        val x = if (layoutDirection == LayoutDirection.Ltr) o else size.width - o - bar
        drawRect(c.accent, Offset(x, o), Size(bar, size.height - 2 * o))
    }
    drawOutline(c.outline)
}

/** A square stroke inside the bounds (SwiftUI's `strokeBorder`). */
internal fun DrawScope.drawOutline(
    color: Color,
    width: Dp = CofreBlock.outline,
    inset: Float = 0f,
    topLeft: Offset = Offset.Zero,
    rect: Size = size,
) {
    val w = width.toPx()
    drawRect(
        color,
        topLeft = Offset(topLeft.x + inset + w / 2, topLeft.y + inset + w / 2),
        size = Size(rect.width - 2 * inset - w, rect.height - 2 * inset - w),
        style = Stroke(w),
    )
}

/** A 2 dp outline stroked inside the bounds, as on wells, fields and badges. */
public fun Modifier.outline(color: Color? = null, width: Dp = CofreBlock.outline): Modifier =
    this then OutlineElement(color, width)

private data class OutlineElement(val color: Color?, val width: Dp) : ModifierNodeElement<OutlineNode>() {
    override fun create() = OutlineNode(color, width)
    override fun update(node: OutlineNode) {
        node.color = color
        node.width = width
        node.invalidateDraw()
    }
}

private class OutlineNode(var color: Color?, var width: Dp) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    override fun ContentDrawScope.draw() {
        drawContent()
        drawOutline(color ?: currentValueOf(LocalCofreColors).outline, width)
    }
}

// Page background (stone texture)

/** Window background: `page` plus the stone texture, 8 dp cells tiled every 128 dp. */
public fun Modifier.pageBackground(): Modifier = drawWithCache {
    val cell = 8.dp.toPx()
    val tile = StoneTexture.tile(cell)
    onDrawBehind {
        drawRect(StoneTexture.page)
        var y = 0f
        while (y < size.height) {
            var x = 0f
            while (x < size.width) {
                drawImage(tile, Offset(x, y), alpha = 0.9f)
                x += tile.width
            }
            y += tile.height
        }
    }
}

internal object StoneTexture {
    val page = CofreColors.stone[0]

    /** 16×16 cells from the mockups' seeded generator (seed 5), rows from the top. */
    val cells: List<Color> = run {
        val pal = CofreColors.stone
        val rng = Mulberry32(5)
        List(256) { pal[(rng.next() * rng.next() * pal.size * 1.6).toInt() % pal.size] }
    }

    fun tile(cell: Float): ImageBitmap {
        val side = (cell * 16).toInt().coerceAtLeast(16)
        val bitmap = ImageBitmap(side, side)
        val k = side / 16f
        CanvasDrawScope().draw(androidx.compose.ui.unit.Density(1f), LayoutDirection.Ltr, Canvas(bitmap), IntSize(side, side).toSize()) {
            cells.forEachIndexed { i, color ->
                drawRect(color, Offset(i % 16 * k, i / 16 * k), Size(k, k))
            }
        }
        return bitmap
    }
}

/** Same seeded generator as the mockups (`rng` in ipad.html), so scenes and textures match iOS. */
internal class Mulberry32(seed: Int) {
    private var a = seed

    fun next(): Double {
        a += 0x6D2B79F5
        var t = a
        t = (t xor (t ushr 15)) * (1 or t)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        return (t xor (t ushr 14)).toUInt().toDouble() / 4_294_967_296.0
    }

    fun int(range: IntRange): Int {
        if (range.last <= range.first) return range.first
        return range.first + (next() * (range.last - range.first + 1)).toInt()
    }

    fun chance(p: Double): Boolean = next() < p
}
