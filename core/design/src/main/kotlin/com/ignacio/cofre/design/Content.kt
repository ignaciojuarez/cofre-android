package com.ignacio.cofre.design

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// §2 #24 LogView, #26 ColumnHeader, the mod-page gallery and RemoteImage.

public enum class LogLevel { Info, Warning, Error, Success }

/** One log line: "12:04:01 [tag] text", the tag colored by [level]. */
@Immutable
public data class LogLine(val time: String, val tag: String, val text: String, val level: LogLevel = LogLevel.Info)

/**
 * A live or static log in a well. [follow] keeps the newest line in view. With a controller it's one focusable
 * block: up/down scroll a few lines, L2/R2 a page.
 */
@Composable
public fun LogView(
    lines: List<LogLine>,
    modifier: Modifier = Modifier,
    follow: Boolean = true,
) {
    val c = CofreTheme.colors
    val code = CofreTheme.type.code.style
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    if (follow) LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.lastIndex) }
    LazyColumn(
        modifier
            .background(c.well)
            .outline()
            .focusRing()
            .focusable()
            .onKeyEvent { e ->
                val code0 = e.nativeKeyEvent.keyCode
                val page = state.layoutInfo.viewportSize.height * 0.85f
                val by = when {
                    code0 == AndroidKeyEvent.KEYCODE_DPAD_UP && state.canScrollBackward -> -page / 6
                    code0 == AndroidKeyEvent.KEYCODE_DPAD_DOWN && state.canScrollForward -> page / 6
                    padFor(code0, false) == Pad.L2 -> -page
                    padFor(code0, false) == Pad.R2 -> page
                    else -> return@onKeyEvent false
                }
                if (e.type == KeyEventType.KeyDown) scope.launch { state.animateScrollBy(by) }
                true
            }
            .semantics(mergeDescendants = true) {
                contentDescription = "Log, " + lines.takeLast(3).joinToString(". ") { it.text }
            },
        state = state,
        contentPadding = PaddingValues(10.dp),
    ) {
        itemsIndexed(lines) { _, line ->
            BasicText(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.muted)) { append(line.time + " ") }
                    val tag = when (line.level) {
                        LogLevel.Warning -> c.warn
                        LogLevel.Error -> c.danger
                        LogLevel.Success -> c.success
                        LogLevel.Info -> c.accent
                    }
                    withStyle(SpanStyle(color = tag)) { append("[${line.tag}] ") }
                    withStyle(SpanStyle(color = c.inkSoft)) { append(line.text) }
                },
                Modifier.fillMaxWidth(),
                style = code,
            )
        }
    }
}

/** Set by layouts that push a detail ([AdaptiveListDetail] on compact); [ColumnHeader] shows a back button. */
@Suppress("ComposeCompositionLocalUsage")
public val LocalColumnBack: ProvidableCompositionLocal<(() -> Unit)?> = staticCompositionLocalOf { null }

/** A column's title with trailing actions, and a back button when the column was pushed. */
@Composable
public fun ColumnHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val compact = CofreTheme.widthClass == WidthClass.Compact
    val back = LocalColumnBack.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 46.dp else 56.dp)
            .padding(horizontal = if (compact) 16.dp else 20.dp)
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        back?.let { BackGlyphButton("Back", it) }
        CofreText(
            title,
            if (compact) CofreTheme.type.headerCompact else CofreTheme.type.header,
            Modifier.weight(1f).semantics { heading() },
            maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

// Remote images

/** Draws [url] scaled by [contentScale]; the app provides it (Coil) through [LocalRemoteImage]. */
public typealias RemoteImageLoader = @Composable (url: String, contentScale: ContentScale, pixelated: Boolean, modifier: Modifier) -> Unit

/** How remote images load. The default draws nothing, so only the placeholder shows (previews, tests). */
@Suppress("ComposeCompositionLocalUsage")
public val LocalRemoteImage: ProvidableCompositionLocal<RemoteImageLoader> = staticCompositionLocalOf { { _, _, _, _ -> } }

/** A remote image over [placeholder]. [pixelated] keeps pixel art (mod icons) sharp when it's scaled up. */
@Composable
public fun RemoteImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    pixelated: Boolean = false,
    placeholder: @Composable () -> Unit = {},
) {
    Box(modifier.clipToBounds()) {
        placeholder()
        LocalRemoteImage.current(url, contentScale, pixelated, Modifier.fillMaxSize())
    }
}

// Gallery

/** A gallery entry: an image, or a video that opens elsewhere. [label] is what TalkBack reads. */
@Immutable
public data class GalleryItem(
    val thumb: String,
    val label: String,
    val video: Boolean = false,
    val full: String? = null,
    val title: String? = null,
    val caption: String? = null,
)

/** A mod page's gallery: 2, 3 or 4 columns of 16:9 cells by width. Videos show a play block. */
@Composable
public fun GalleryGrid(
    items: List<GalleryItem>,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val columns = when (CofreTheme.widthClass) {
        WidthClass.Compact -> 2
        WidthClass.Medium -> 3
        WidthClass.Expanded -> 4
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.withIndex().chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (i, item) -> GalleryCell(item, { onOpen(i) }, Modifier.weight(1f)) }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun GalleryCell(
    item: GalleryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CofreTheme.colors
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .blockClickable(onClick = onClick)
            .semantics { contentDescription = item.label }
            .background(c.well)
            .outline(),
        contentAlignment = Alignment.Center,
    ) {
        RemoteImage(item.thumb, Modifier.fillMaxSize().padding(CofreBlock.outline), ContentScale.Crop)
        if (item.video) {
            Box(Modifier.size(44.dp, 36.dp).block(BlockKind.Primary), contentAlignment = Alignment.Center) {
                PixelIcon(PixelGlyph.Play, size = 18.dp, foreground = c.onFace)
            }
        }
    }
}

/**
 * Full-screen image viewer: swipe, D-pad or L1/R1 between images; B or ✕ closes. The thumbnail shows at once and
 * the full image loads over it.
 */
@Composable
public fun GalleryViewer(
    images: List<GalleryItem>,
    start: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CofreTheme.colors
    val pager = rememberPagerState(start.coerceIn(0, (images.size - 1).coerceAtLeast(0))) { images.size }
    val scope = rememberCoroutineScope()
    fun step(by: Int) = scope.launch { pager.animateScrollToPage((pager.currentPage + by).coerceIn(0, images.lastIndex)) }
    BackHandler(onBack = onClose)
    ScreenFocus(
        modifier
            .fillMaxSize()
            .background(c.lip)
            .controllerActions(
                ControllerAction(Pad.L1, "Previous") { step(-1) },
                ControllerAction(Pad.R1, "Next") { step(1) },
                ControllerAction(Pad.Back, "Close"),
            ),
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
            val image = images[i]
            Box(Modifier.fillMaxSize().semantics { contentDescription = image.label }) {
                RemoteImage(image.thumb, Modifier.fillMaxSize())
                if (image.full != null && image.full != image.thumb && kotlin.math.abs(i - pager.currentPage) <= 1) {
                    RemoteImage(image.full, Modifier.fillMaxSize())
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(PixelGlyph.Cross, "Close", onClose)
            CofreText(
                "${pager.currentPage + 1} / ${images.size}",
                CofreTheme.type.label,
                Modifier.weight(1f),
                color = c.inkSoft,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Box(Modifier.size(32.dp))
        }
        images.getOrNull(pager.currentPage)?.takeIf { it.title != null || it.caption != null }?.let { image ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(c.lip.copy(alpha = 0.8f))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                image.title?.let { CofreText(it, CofreTheme.type.rowTitle) }
                image.caption?.let { CofreText(it, CofreTheme.type.secondary, color = c.inkSoft) }
            }
        }
    }
}
