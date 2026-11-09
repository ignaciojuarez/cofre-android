package com.ignacio.cofre.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// §2 #22 Sheet: a bottom sheet on compact, a centered card elsewhere. Opaque panel, no glass.

/**
 * A modal sheet in its own window. It takes focus when it opens (in controller mode) and, when it closes, focus
 * is back on whatever opened it. B, system back and a tap outside call [onDismissRequest].
 *
 * [hugsContent]: as tall as its content on compact, and a 420 dp card elsewhere.
 */
@Composable
public fun BlockSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    hugsContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val controller = LocalController.current
    Dialog(
        onDismissRequest,
        DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CompositionLocalProvider(LocalController provides controller) {
            WindowInput(controller, onBack = onDismissRequest)
            BoxWithConstraints(Modifier.fillMaxSize().controllerInput(onBack = onDismissRequest)) {
                val compact = WidthClass.of(maxWidth) == WidthClass.Compact
                CompositionLocalProvider(LocalWidthClass provides if (compact) WidthClass.Compact else WidthClass.Medium) {
                    SheetPanel(
                        modifier.align(if (compact) Alignment.BottomCenter else Alignment.Center),
                        compact = compact,
                        hugsContent = hugsContent,
                        maxHeight = maxHeight,
                        content = content,
                    )
                }
            }
        }
    }
}

/**
 * The sheet's surface on its own, for layouts that draw it in place (the design gallery, previews).
 * [compact] draws the bottom-sheet form.
 */
@Composable
public fun SheetPanel(
    modifier: Modifier = Modifier,
    compact: Boolean = CofreTheme.widthClass == WidthClass.Compact,
    hugsContent: Boolean = false,
    maxHeight: Dp = Dp.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = CofreTheme.colors
    val shape = if (compact) {
        modifier
            .fillMaxWidth()
            .then(if (hugsContent || maxHeight == Dp.Unspecified) Modifier else Modifier.fillMaxHeight(0.75f))
            .background(c.panel)
            .drawBehind { drawRect(c.outline, size = Size(size.width, CofreBlock.outline.toPx())) }
    } else {
        modifier
            .width(if (hugsContent) 420.dp else CofreSpacing.card)
            .heightIn(max = if (maxHeight == Dp.Unspecified) Dp.Unspecified else maxHeight - 48.dp)
            .block(lip = CofreBlock.buttonLip)
    }
    ScreenFocus(claim = true, shape) {
        Column(Modifier.padding(bottom = 16.dp)) {
            if (compact) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(36.dp, 4.dp)
                        .background(c.muted)
                        .align(Alignment.CenterHorizontally),
                )
            }
            content()
        }
    }
}

/** Sheet header: a close button ("‹"), the title, and trailing actions. */
@Composable
public fun SheetHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackGlyphButton("Close", onClose)
        CofreText(title, CofreTheme.type.header, Modifier.weight(1f).semantics { heading() }, maxLines = 1)
        trailing()
    }
}

/** The accent "‹" used by sheet and column headers. */
@Composable
internal fun BackGlyphButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(44.dp)
            .blockClickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.CenterStart,
    ) {
        CofreText("‹", CofreTheme.type.header, color = CofreTheme.colors.accent)
    }
}
