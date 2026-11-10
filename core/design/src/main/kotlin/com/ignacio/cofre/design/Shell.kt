package com.ignacio.cofre.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// §3.2 AdaptiveShell and §2 #27 NavChrome: one section list drawn as a 76 dp rail (medium) or a 248 dp sidebar
// (expanded). Cofre is landscape only, so there's no tab bar.

/** The shell's sections. */
public enum class ShellSection(public val title: String, public val glyph: PixelGlyph) {
    Play("Play", PixelGlyph.Play),
    Instances("Instances", PixelGlyph.Grass),
    Mods("Mods", PixelGlyph.Book),
    Settings("Settings", PixelGlyph.Gear),
}

/** How the shell draws its navigation. */
public enum class NavStyle { Rail, Sidebar }

/**
 * The app frame. Rail under 1100 dp, sidebar above (or a rail when [preferRail]). L1/R1 cycle sections and move focus into the new one's content (its last focused item, else
 * its first); picking a section in the rail or sidebar leaves focus there. Each section keeps its saved state
 * and its last focused item, and the controller hint bar sits under the content.
 *
 * [navFooter] fills the bottom of the rail and sidebar (account, status).
 */
@Composable
public fun AdaptiveShell(
    section: ShellSection,
    onSectionChange: (ShellSection) -> Unit,
    modifier: Modifier = Modifier,
    preferRail: Boolean = false,
    modsBadge: Int = 0,
    navFooter: @Composable ColumnScope.(NavStyle) -> Unit = {},
    content: @Composable (ShellSection) -> Unit,
) {
    val sections = ShellSection.entries
    val holder = rememberSaveableStateHolder()
    val sectionActions = remember { mutableStateListOf<ControllerAction>() }
    // How the section last changed: by shoulder button (focus goes into the content) or from the nav chrome.
    var byShoulder by remember { mutableStateOf(false) }
    fun shoulder(step: Int) {
        byShoulder = true
        onSectionChange(sections.cycle(section, step))
    }
    val pick: (ShellSection) -> Unit = {
        byShoulder = false
        onSectionChange(it)
    }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .pageBackground()
            .controllerActions(
                ControllerAction(Pad.L1, "Sections") { shoulder(-1) },
                ControllerAction(Pad.R1, "Sections") { shoulder(1) },
                *sectionActions.toTypedArray(),
            ),
    ) {
        val widthClass = WidthClass.of(maxWidth)
        val style = if (widthClass == WidthClass.Expanded && !preferRail) NavStyle.Sidebar else NavStyle.Rail
        CompositionLocalProvider(LocalWidthClass provides widthClass, LocalSectionActions provides sectionActions) {
            // One layout for every width, so resizing moves the chrome without rebuilding the content.
            ShellLayout {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        holder.SaveableStateProvider(section) {
                            ScreenFocus(claim = byShoulder, Modifier.fillMaxSize()) { content(section) }
                        }
                    }
                    ControllerHintBar()
                }
                NavChrome(style, section, pick, modsBadge, navFooter)
            }
        }
    }
}

private val LocalSectionActions = staticCompositionLocalOf<MutableList<ControllerAction>?> { null }

/**
 * Gamepad actions for the whole shell, nav chrome included, while the calling section is on screen (Mods' LT folder
 * picker works from the rail too). Regions inside the content still win.
 */
@Composable
public fun SectionActions(vararg actions: ControllerAction) {
    val registry = LocalSectionActions.current ?: return
    val latest by rememberUpdatedState(actions)
    val keys = actions.map { it.pad to it.label }
    DisposableEffect(registry, keys) {
        val stable = keys.map { (pad, label) ->
            ControllerAction(pad, label) { latest.firstOrNull { it.pad == pad }?.onPress?.invoke() }
        }
        registry += stable
        onDispose { registry -= stable.toSet() }
    }
}

/** The nav chrome on the leading edge, the body beside it. */
@Composable
private fun ShellLayout(content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val (body, chrome) = measurables
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val side = chrome.measure(Constraints(maxWidth = w, minHeight = h, maxHeight = h))
        val main = body.measure(Constraints.fixed(w - side.width, h))
        layout(w, h) {
            main.placeRelative(side.width, 0)
            side.placeRelative(0, 0)
        }
    }
}

@Composable
private fun NavChrome(
    style: NavStyle,
    section: ShellSection,
    onSelect: (ShellSection) -> Unit,
    modsBadge: Int,
    footer: @Composable ColumnScope.(NavStyle) -> Unit,
) {
    val c = CofreTheme.colors
    fun badge(s: ShellSection) = if (s == ShellSection.Mods) modsBadge else 0
    val rail = style == NavStyle.Rail
    // Scrolls when the window is too short for every section and the footer (the footer stays at the bottom otherwise).
    BoxWithConstraints(
        Modifier
            .width(if (rail) CofreSpacing.rail else CofreSpacing.sidebar)
            .fillMaxHeight()
            .background(c.side)
            .drawBehind {
                val o = CofreBlock.outline.toPx()
                val x = if (layoutDirection == LayoutDirection.Ltr) size.width - o else 0f
                drawRect(c.outline, Offset(x, 0f), Size(o, size.height))
            },
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .fillMaxWidth()
                .padding(horizontal = if (rail) 6.dp else 12.dp)
                .padding(top = 10.dp, bottom = if (rail) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = if (rail) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            if (rail) {
                AppIcon(Modifier.padding(top = 6.dp, bottom = 12.dp), size = 34.dp)
            } else {
                Row(
                    Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(size = 30.dp)
                    CofreText("Cofre", CofreTheme.type.title2)
                }
            }
            ShellSection.entries.forEach { s -> NavItem(s, s == section, onSelect, style, badge(s)) }
            Spacer(Modifier.weight(1f))
            footer(style)
        }
    }
}

@Composable
private fun NavItem(
    s: ShellSection,
    on: Boolean,
    onSelect: (ShellSection) -> Unit,
    style: NavStyle,
    badge: Int,
    modifier: Modifier = Modifier,
) {
    val c = CofreTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ink = if (on) c.ink else c.muted
    val bar = if (style == NavStyle.Rail) 3.dp else 4.dp
    val base = modifier
        .blockClickable(interaction) { onSelect(s) }
        .semantics { selected = on }
        .background(if (on) c.raised else if (hovered) c.hover else Color.Transparent)
        .drawBehind {
            if (on) {
                val b = bar.toPx()
                val x = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - b
                drawRect(c.accent, Offset(x, 0f), Size(b, size.height))
            }
        }
    when (style) {
        NavStyle.Rail -> Column(
            base.fillMaxWidth().heightIn(min = 62.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                PixelIcon(s.glyph, Modifier.alpha(if (on) 1f else 0.55f), size = 22.dp, foreground = ink)
                CountBadge(badge, Modifier.align(Alignment.TopEnd).padding(start = 26.dp))
            }
            CofreText(s.title, CofreTheme.type.navSmall, color = ink, maxLines = 1)
        }
        NavStyle.Sidebar -> Row(
            base.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelIcon(s.glyph, Modifier.alpha(if (on) 1f else 0.55f), size = 20.dp, foreground = ink)
            CofreText(s.title, CofreTheme.type.nav, Modifier.weight(1f), color = ink, maxLines = 1)
            CountBadge(badge)
        }
    }
}

@Composable
private fun CountBadge(
    n: Int,
    modifier: Modifier = Modifier,
) {
    if (n <= 0) return
    Box(modifier.background(CofreTheme.colors.tint(Tone.Warn)).outline().padding(horizontal = 5.dp).padding(bottom = 1.dp)) {
        CofreText("$n", CofreTheme.type.badge)
    }
}

/** Cofre's amethyst block icon. Decorative. */
@Composable
public fun AppIcon(
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
) {
    val shape = RoundedCornerShape(size * 0.22f)
    Image(
        ImageBitmap.imageResource(R.drawable.cofre_app_icon),
        contentDescription = null,
        modifier = modifier.size(size).clip(shape).border(2.dp, CofreTheme.colors.outline, shape),
        filterQuality = FilterQuality.None,
    )
}
