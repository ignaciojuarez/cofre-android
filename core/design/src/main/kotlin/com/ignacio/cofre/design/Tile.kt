package com.ignacio.cofre.design

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Dashboard tiles: a summary that opens in place ([DashTile]), and the panel it opens to ([TilePanel]).

/**
 * A dashboard tile: icon, label and chevron; a big [value]; a muted [subtitle]; a [footer] (badges, thumbnails). One
 * focus target, so the D-pad moves tile to tile; A opens it. A [tone] other than Neutral tints it and adds a bar on
 * the leading edge (Warn: something to fix, Danger: it won't launch).
 */
@Composable
public fun DashTile(
    label: String,
    icon: PixelGlyph,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tone: Tone = Tone.Neutral,
    minHeight: Dp = 118.dp,
    footer: @Composable RowScope.() -> Unit = {},
) {
    val c = CofreTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bar = if (tone == Tone.Neutral) Color.Transparent else c.tone(tone)
    Column(
        modifier
            .heightIn(min = minHeight)
            .blockClickable(interaction, onClick = onClick)
            .block()
            .innerOverlay(if (tone == Tone.Neutral) Color.Transparent else c.tint(tone, amount = 0.14f))
            .innerOverlay(if (hovered) c.hover else Color.Transparent)
            .drawBehind {
                if (bar != Color.Transparent) {
                    val o = CofreBlock.outline.toPx()
                    drawRect(bar, Offset(o, o), Size(4.dp.toPx(), size.height - 2 * o))
                }
            }
            .padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelIcon(icon, size = 16.dp)
            CofreText(label, CofreTheme.type.label, Modifier.weight(1f), color = c.muted, maxLines = 1)
            Chevron()
        }
        CofreText(value, CofreTheme.type.title2, color = if (tone == Tone.Danger || tone == Tone.Warn) c.tone(tone) else c.ink, maxLines = 1)
        subtitle?.let { CofreText(it, CofreTheme.type.caption, color = c.inkSoft, maxLines = 2) }
        Box(Modifier.weight(1f, fill = false))
        Row(
            Modifier.fillMaxWidth().padding(top = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = footer,
        )
    }
}

/**
 * What a [DashTile] opens to, drawn in its place: a header with [closeLabel] (focused first), the icon and [title],
 * then [content] in a scrolling column. B or [closeLabel] calls [onClose]; the caller puts focus back on the tile.
 */
@Composable
public fun TilePanel(
    title: String,
    icon: PixelGlyph,
    closeLabel: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val first = remember { FocusRequester() }
    val scroll = rememberScrollState()
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { first.requestFocus() }
    }
    Column(
        modifier
            .controllerActions(ControllerAction(Pad.Back, closeLabel))
            .block(lip = CofreBlock.buttonLip),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BlockButton("‹ $closeLabel", onClose, Modifier.focusRequester(first), size = ButtonSize.Small)
            PixelIcon(icon, size = 18.dp)
            CofreText(title, CofreTheme.type.title2, Modifier.weight(1f).semantics { heading() }, maxLines = 1)
            trailing()
        }
        Column(
            Modifier.fillMaxSize().pageScroll(scroll).verticalScroll(scroll).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
