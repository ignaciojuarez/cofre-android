package com.ignacio.cofre.design

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// §2 #4 Row, ToggleRow, ValueRow, #5 ListSection, #7 Switch.

/**
 * A list row: leading · title/subtitle · trailing on a panel block. Tappable when [onClick] is set; the block
 * sinks into a well while pressed. [selected] gets the raised fill and the accent bar.
 */
@Composable
public fun BlockRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    big: Boolean = false,
    dimmed: Boolean = false,
    onClick: (() -> Unit)? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val c = CofreTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = CofreSpacing.rowMin)
            .then(if (onClick != null) Modifier.blockClickable(interaction, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .alpha(if (dimmed) 0.5f else 1f)
            .block(selected = selected)
            .innerOverlay(if (hovered) c.hover else Color.Transparent)
            .then(if (pressed) Modifier.sunken() else Modifier)
            .padding(horizontal = 11.dp, vertical = if (big) 10.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            CofreText(title, CofreTheme.type.rowTitle, maxLines = 1)
            when {
                subtitleContent != null -> subtitleContent()
                subtitle != null -> CofreText(subtitle, CofreTheme.type.caption, color = c.muted, maxLines = 1)
            }
        }
        trailing?.invoke()
    }
}

/** Pressed rows sink into a well: a dark wash with a lip line along the top (§1.4). */
private fun Modifier.sunken(): Modifier = drawWithContent {
    drawContent()
    val o = CofreBlock.outline.toPx()
    val c = Size(size.width - 2 * o, size.height - 2 * o)
    drawRect(Color(0xff0c0b12).copy(alpha = 0.55f), Offset(o, o), c)
    drawRect(Color(0xff07060b), Offset(o, o), Size(c.width, 2.dp.toPx()))
}

/** A row whose trailing switch toggles with the whole row. */
@Composable
public fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = CofreSpacing.rowMin)
            .focusRing()
            .toggleable(checked, interaction, null, enabled, Role.Switch, onCheckedChange)
            .alpha(if (enabled) 1f else CofreBlock.DISABLED_ALPHA)
            .block()
            .innerOverlay(if (hovered) CofreTheme.colors.hover else Color.Transparent)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            CofreText(title, CofreTheme.type.rowTitle)
            subtitle?.let { CofreText(it, CofreTheme.type.caption, color = CofreTheme.colors.muted) }
        }
        SwitchTrack(checked)
    }
}

/** A row that shows a value and opens or pushes ("Renderer · Vulkan ›"). */
@Composable
public fun ValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    chevron: Boolean = true,
) {
    BlockRow(
        title = title,
        modifier = modifier,
        onClick = onClick,
        trailing = {
            CofreText(
                if (chevron) "$value ›" else value,
                CofreTheme.type.label,
                color = if (accent) CofreTheme.colors.accent else CofreTheme.colors.muted,
                maxLines = 1,
            )
        },
    )
}

/** Trailing chevron for rows that push. */
@Composable
public fun Chevron(modifier: Modifier = Modifier) {
    CofreText("›", CofreTheme.type.button, modifier, color = CofreTheme.colors.muted)
}

/** A mono section label over a 5 dp stack of rows. */
@Composable
public fun ListSection(
    modifier: Modifier = Modifier,
    label: String? = null,
    spacing: Dp = 5.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.let { SectionLabel(it) }
        Column(verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

/** Mono uppercase section label, a heading for TalkBack. */
@Composable
public fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    CofreText(
        text,
        CofreTheme.type.label,
        modifier.fillMaxWidth().padding(top = 4.dp).semantics { heading() },
        color = CofreTheme.colors.muted,
    )
}

/** Mono uppercase trailing meta ("2 h", "24 ms"), muted or in a tone. */
@Composable
public fun Meta(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone? = null,
) {
    CofreText(text, CofreTheme.type.label, modifier, color = tone?.let { CofreTheme.colors.tone(it) } ?: CofreTheme.colors.muted, maxLines = 1)
}

/** A standalone block switch. The off thumb is `inkSoft` so the state reads at 3:1. */
@Composable
public fun BlockSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SwitchTrack(
        checked,
        modifier
            .focusRing()
            .toggleable(checked, null, null, enabled, Role.Switch, onCheckedChange)
            .alpha(if (enabled) 1f else CofreBlock.DISABLED_ALPHA),
    )
}

/** 120 ms in three steps (§1.5). */
private val ThreeSteps = Easing { (it * 3).toInt() / 3f }

@Composable
private fun SwitchTrack(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = CofreTheme.colors
    val spec = if (CofreTheme.reduceMotion) snap() else tween<Float>(CofreMotion.TOGGLE, easing = ThreeSteps)
    val t by animateFloatAsState(if (checked) 1f else 0f, spec, label = "thumb")
    Canvas(modifier.size(40.dp, 22.dp)) {
        drawRect(if (checked) c.face else c.well)
        val thumb = 18.dp.toPx()
        val x = (size.width - thumb) * t
        val y = (size.height - thumb) / 2
        drawRect(if (checked) Color.White else c.inkSoft, Offset(x, y), Size(thumb, thumb))
        val shade = 2.dp.toPx()
        drawRect(if (checked) Color(0xffc9c9c9) else c.muted, Offset(x, y + thumb - shade), Size(thumb, shade))
        drawOutline(c.outline, topLeft = Offset(x, y), rect = Size(thumb, thumb))
        drawOutline(c.outline)
    }
}
