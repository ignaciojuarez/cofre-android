package com.ignacio.cofre.design

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// §3.2 containers: AdaptiveListDetail, ReadableColumn, PaneScroll, CenteredCard.

/**
 * List and detail. Side by side when there's room (a 330–350 dp list plus at least 360 dp of detail); on compact
 * the detail replaces the list, with a back button in its [ColumnHeader] and on B / system back. Selection is
 * hoisted, so a resize keeps it. Each side keeps its saved state and focused item.
 */
@Composable
public fun <T : Any> AdaptiveListDetail(
    selection: T?,
    onSelectionChange: (T?) -> Unit,
    list: @Composable () -> Unit,
    detail: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = { EmptyState("Nothing selected", "Pick an item from the list.", PixelGlyph.Chest) },
) {
    val widthClass = CofreTheme.widthClass
    val holder = rememberSaveableStateHolder()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val listWidth = if (widthClass == WidthClass.Expanded) 350.dp else 330.dp
        val ownWidth = maxWidth
        val sideBySide = widthClass != WidthClass.Compact && ownWidth >= listWidth + 360.dp
        if (sideBySide) {
            ScreenFocus(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .width(listWidth)
                            .fillMaxHeight()
                            .background(CofreTheme.colors.side.copy(alpha = 0.55f))
                            .trailingRule(),
                    ) { holder.SaveableStateProvider("list") { list() } }
                    val detailClass = if (ownWidth - listWidth >= 700.dp) widthClass else WidthClass.Medium
                    CompositionLocalProvider(LocalWidthClass provides detailClass) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (selection != null) holder.SaveableStateProvider(selection) { detail(selection) } else placeholder()
                        }
                    }
                }
            }
        } else {
            CompositionLocalProvider(LocalWidthClass provides WidthClass.Compact) {
                if (selection != null) {
                    BackHandler { onSelectionChange(null) }
                    CompositionLocalProvider(LocalColumnBack provides { onSelectionChange(null) }) {
                        holder.SaveableStateProvider(selection) { ScreenFocus(Modifier.fillMaxSize()) { detail(selection) } }
                    }
                } else {
                    holder.SaveableStateProvider("list") { ScreenFocus(Modifier.fillMaxSize()) { list() } }
                }
            }
        }
    }
}

/** A 2 dp outline on the trailing edge (list columns, the rail). */
internal fun Modifier.trailingRule(): Modifier = drawBehind {
    val o = CofreBlock.outline.toPx()
    val x = if (layoutDirection == LayoutDirection.Ltr) size.width - o else 0f
    drawRect(Color(0xff05040a), Offset(x, 0f), Size(o, size.height))
}

/** Full width on compact; at most [maxWidth] and centered elsewhere, with the pane margins. */
@Composable
public fun ReadableColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp = CofreSpacing.readable,
    content: @Composable ColumnScope.() -> Unit,
) {
    val compact = CofreTheme.widthClass == WidthClass.Compact
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(horizontal = if (compact) 16.dp else 20.dp),
            content = content,
        )
    }
}

/** The standard pane: a scrolling column with the pane margins and gaps. L2/R2 page through it. */
@Composable
public fun PaneScroll(
    modifier: Modifier = Modifier,
    spacing: Dp = 10.dp,
    readable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val compact = CofreTheme.widthClass == WidthClass.Compact
    val scroll = rememberScrollState()
    val gap = if (compact) minOf(spacing, 8.dp) else spacing
    Column(
        modifier
            .fillMaxSize()
            .pageScroll(scroll)
            .verticalScroll(scroll)
            .padding(top = 6.dp, bottom = 22.dp),
    ) {
        if (readable) {
            ReadableColumn { Column(verticalArrangement = Arrangement.spacedBy(spacing), content = content) }
        } else {
            Column(
                Modifier.padding(horizontal = if (compact) 16.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(gap),
                content = content,
            )
        }
    }
}

/** Full screen on compact; a 560 dp card on the page texture elsewhere (setup, ready screens). */
@Composable
public fun CenteredCard(
    modifier: Modifier = Modifier,
    width: Dp = CofreSpacing.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    val compact = CofreTheme.widthClass == WidthClass.Compact
    val scroll = rememberScrollState()
    Box(modifier.fillMaxSize().pageBackground().pageScroll(scroll)) {
        if (compact) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(vertical = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CompositionLocalProvider(LocalWidthClass provides WidthClass.Medium) {
                    Column(
                        Modifier
                            .width(width)
                            .dropShadow(RectangleShape, Shadow(40.dp, Color.Black.copy(alpha = 0.6f), offset = DpOffset(0.dp, 30.dp)))
                            .block(lip = CofreBlock.buttonLip)
                            .padding(start = 28.dp, end = 28.dp, top = 26.dp, bottom = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content,
                    )
                }
            }
        }
    }
}
