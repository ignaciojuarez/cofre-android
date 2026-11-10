package com.ignacio.cofre.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// §2 #3 Badge, #12 Radio, #13 ProgressBar, #14 Spinner, #15 StepBar, #19 Stat, #20 Banner, #21 EmptyState.

/** Small status label. Read as part of its row; never the only signal of state. */
@Composable
public fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    big: Boolean = false,
) {
    val c = CofreTheme.colors
    Box(
        modifier
            .heightIn(min = if (big) 26.dp else 19.dp)
            .background(if (tone == Tone.Neutral) c.well else c.tint(tone))
            .outline()
            .padding(horizontal = if (big) 9.dp else 6.dp)
            .padding(bottom = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        CofreText(
            text,
            if (big) CofreTheme.type.buttonS else CofreTheme.type.badge,
            color = if (tone == Tone.Neutral) c.inkSoft else c.ink,
            maxLines = 1,
        )
    }
}

/** Warn · ok · bad · info strip with an optional trailing [action]. Danger banners are announced. */
@Composable
public fun Banner(
    title: String,
    icon: PixelGlyph,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Warn,
    action: @Composable RowScope.() -> Unit = {},
) {
    val c = CofreTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .block()
            .innerOverlay(c.tint(if (tone == Tone.Neutral) Tone.Accent else tone, amount = 0.16f))
            .semantics { if (tone == Tone.Danger) liveRegion = LiveRegionMode.Assertive }
            .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelIcon(icon, size = 18.dp)
        CofreText(title, CofreTheme.type.rowTitle, Modifier.weight(1f))
        action()
    }
}

/** Radio mark: a row's lead; the row carries the selection. */
@Composable
public fun Radio(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = CofreTheme.colors
    Canvas(modifier.size(16.dp)) {
        drawRect(c.well)
        drawOutline(c.outline)
        if (selected) {
            val k = 6.dp.toPx()
            val o = (size.width - k) / 2
            drawRect(c.accent, Offset(o, o), Size(k, k))
            drawOutline(c.outline, 1.dp, inset = o - 1.dp.toPx())
        }
    }
}

/** XP-style progress: fills in 9 dp segments with no tween. A `null` [fraction] shows a Spinner and [label]. */
@Composable
public fun ProgressBar(
    fraction: Float?,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Success,
    small: Boolean = false,
    label: String = "",
) {
    val c = CofreTheme.colors
    if (fraction == null) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spinner()
            CofreText(label, CofreTheme.type.label, color = c.muted)
        }
        return
    }
    val color = if (tone == Tone.Neutral) c.inkSoft else c.tone(tone)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(if (small) 12.dp else 16.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f) },
    ) {
        drawRect(c.well)
        val pad = 4.dp.toPx()
        val seg = 11.dp.toPx()
        val count = ((size.width - 2 * pad) * fraction.coerceIn(0f, 1f) / seg).toInt()
        val barH = (if (small) 4.dp else 8.dp).toPx()
        repeat(count) { i -> drawRect(color, Offset(pad + i * seg, pad), Size(9.dp.toPx(), barH)) }
        drawOutline(c.outline)
    }
}

/** Four accent pixels blinking in turn, a quarter second each. Static when animations are off. */
@Composable
public fun Spinner(
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    val accent = CofreTheme.colors.accent
    val reduceMotion = CofreTheme.reduceMotion
    var phase by remember { mutableIntStateOf(-1) }
    if (!reduceMotion) {
        LaunchedEffect(Unit) {
            while (true) withInfiniteAnimationFrameMillis { phase = (it / 250 % 4).toInt() }
        }
    }
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val gap = 2.dp.toPx()
        val cell = (this.size.width - gap) / 2
        val order = intArrayOf(0, 1, 3, 2) // clockwise
        for (i in 0 until 4) {
            val dim = phase >= 0 && order[phase] == i
            drawRect(
                accent.copy(alpha = if (dim) 0.2f else 1f),
                Offset(i % 2 * (cell + gap), i / 2 * (cell + gap)),
                Size(cell, cell),
            )
        }
    }
}

/** Setup steps: a bar and a name each. [current] is 0-based. */
@Composable
public fun StepBar(
    steps: List<String>,
    current: Int,
    modifier: Modifier = Modifier,
) {
    val c = CofreTheme.colors
    Row(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = "Step ${current + 1} of ${steps.size}, ${steps.getOrElse(current) { "" }}"
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        steps.forEachIndexed { i, step ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .background(
                            when {
                                i < current -> c.face
                                i == current -> c.accent
                                else -> c.well
                            },
                        )
                        .outline(),
                )
                CofreText(step, CofreTheme.type.label, color = if (i == current) c.ink else c.muted, maxLines = 1)
            }
        }
    }
}

/** A value and its label in a panel ("142 · mods"). */
@Composable
public fun Stat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
) {
    val c = CofreTheme.colors
    Column(
        modifier
            .block()
            .semantics(mergeDescendants = true) {}
            .padding(start = 8.dp, end = 8.dp, top = 9.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CofreText(value, CofreTheme.type.stat, color = if (tone == Tone.Neutral) c.ink else c.tone(tone), maxLines = 1)
        CofreText(label, CofreTheme.type.label, color = c.muted, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** No instances · no results · offline. The title is a heading; inside a [ScreenFocus] the action gets focus. */
@Composable
public fun EmptyState(
    title: String,
    message: String,
    icon: PixelGlyph,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PixelIcon(icon, size = 44.dp)
        CofreText(title, CofreTheme.type.title2, Modifier.semantics { heading() })
        CofreText(message, CofreTheme.type.secondary, color = CofreTheme.colors.muted, textAlign = TextAlign.Center)
        Box(Modifier.widthIn(max = 260.dp)) { action() }
    }
}
