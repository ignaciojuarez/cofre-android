package com.ignacio.cofre.design

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

// §2 #6 Segmented, #8 Slider, #9 Field, #10 SelectButton, #11 Chip / ChipGroup.

/** A well with face-colored on-segments. D-pad moves between segments; A picks one. */
@Composable
public fun <T> Segmented(
    selection: T,
    onSelect: (T) -> Unit,
    options: List<Pair<T, String>>,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    val c = CofreTheme.colors
    Row(
        modifier.height(IntrinsicSize.Min).background(c.well).outline().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selection
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = if (small) 24.dp else 28.dp)
                    .focusRing()
                    .selectable(on, interaction, null, role = Role.Tab) { onSelect(value) }
                    .drawBehind {
                        when {
                            on -> {
                                val b = 2.dp.toPx()
                                drawRect(c.face)
                                drawRect(c.faceBevel, size = Size(size.width, b))
                                drawRect(c.faceLip, Offset(0f, size.height - b), Size(size.width, b))
                            }
                            hovered -> drawRect(c.hover)
                        }
                    }
                    .padding(horizontal = 6.dp)
                    .padding(bottom = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                CofreText(
                    label,
                    if (small) CofreTheme.type.chip else CofreTheme.type.buttonS,
                    color = if (on) c.onFace else c.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A labelled slider in a panel: value text on the right, optional [note] and default tick. Drag or tap the
 * track; with a controller, focus it and press left/right.
 */
@Composable
public fun BlockSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String,
    modifier: Modifier = Modifier,
    step: Float = 1f,
    note: String? = null,
    defaultValue: Float? = null,
) {
    val c = CofreTheme.colors
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    fun snap(v: Float) = ((v / step).roundToInt() * step).coerceIn(valueRange.start, valueRange.endInclusive)
    fun fraction(v: Float) =
        ((v - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    Column(
        modifier
            .fillMaxWidth()
            .block()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CofreText(title, CofreTheme.type.rowTitle, Modifier.weight(1f))
            CofreText(valueText(value), CofreTheme.type.label, color = c.inkSoft)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .focusRing()
                .focusable()
                .onKeyEvent { e ->
                    val delta = when (e.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> -step
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> step
                        else -> return@onKeyEvent false
                    }
                    if (e.type == KeyEventType.KeyDown) change(snap(current + delta))
                    true
                }
                .semantics {
                    contentDescription = title
                    progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps = 0)
                    setProgress { change(snap(it)); true }
                }
                .pointerInput(valueRange, step) {
                    fun at(x: Float) = change(
                        snap(valueRange.start + (x - 7.dp.toPx()) / (size.width - 14.dp.toPx()).coerceAtLeast(1f) * (valueRange.endInclusive - valueRange.start)),
                    )
                    detectTapGestures { at(it.x) }
                }
                .pointerInput(valueRange, step) {
                    fun at(x: Float) = change(
                        snap(valueRange.start + (x - 7.dp.toPx()) / (size.width - 14.dp.toPx()).coerceAtLeast(1f) * (valueRange.endInclusive - valueRange.start)),
                    )
                    detectHorizontalDragGestures(onDragStart = { at(it.x) }) { change, _ -> at(change.position.x) }
                },
        ) {
            val thumbW = 14.dp.toPx()
            val x = (size.width - thumbW) * fraction(value)
            val trackH = 12.dp.toPx()
            val top = (size.height - trackH) / 2
            drawRect(c.well, Offset(0f, top), Size(size.width, trackH))
            drawRect(c.face, Offset(0f, top), Size(x + thumbW / 2, trackH))
            drawOutline(c.outline, topLeft = Offset(0f, top), rect = Size(size.width, trackH))
            defaultValue?.let {
                val tx = (size.width - thumbW) * fraction(it) + 6.dp.toPx()
                drawRect(c.outline, Offset(tx, size.height / 2 + 9.dp.toPx()), Size(2.dp.toPx(), 8.dp.toPx()))
            }
            val thumbH = 22.dp.toPx()
            val ty = (size.height - thumbH) / 2
            drawRect(Color.White, Offset(x, ty), Size(thumbW, thumbH))
            drawRect(Color(0xffc9c9c9), Offset(x, ty + thumbH - 3.dp.toPx()), Size(thumbW, 3.dp.toPx()))
            drawOutline(c.outline, topLeft = Offset(x, ty), rect = Size(thumbW, thumbH))
        }
        note?.let { CofreText(it, CofreTheme.type.label, color = c.muted) }
    }
}

public enum class FieldKind { Plain, Search, Secure, Mono }

/**
 * Text input in a well. Editing shows an accent hairline; an [error] gets a danger outline and its message
 * underneath. [Mono] grows to several lines.
 */
@Composable
public fun Field(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = label,
    kind: FieldKind = FieldKind.Plain,
    error: String? = null,
    trailingNote: String? = null,
    enabled: Boolean = true,
) {
    val c = CofreTheme.colors
    val type = CofreTheme.type
    val interaction = remember { MutableInteractionSource() }
    val editing by interaction.collectIsFocusedAsState()
    val textRole = if (kind == FieldKind.Mono || kind == FieldKind.Secure) type.code else type.secondary
    val style = textRole.style.copy(color = c.ink)
    val decorate: @Composable (@Composable () -> Unit) -> Unit = { inner ->
        Row(
            Modifier
                .heightIn(min = CofreSpacing.field)
                .background(c.well)
                .outline(if (error != null) c.danger else c.outline)
                .drawBehind {
                    if (editing) drawOutline(c.accent, 1.dp, inset = CofreBlock.outline.toPx())
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (kind == FieldKind.Search) PixelArt(SearchGlyph, mapOf('c' to c.muted), pixel = 1.5.dp)
            Box(Modifier.weight(1f)) {
                if (state.text.isEmpty()) CofreText(placeholder, textRole, color = c.muted, maxLines = 1)
                inner()
            }
            trailingNote?.let { CofreText(it, type.label, color = c.muted) }
        }
    }
    val fieldModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 3.dp)
        .focusRing()
        .semantics { contentDescription = label }
    Column(modifier.alpha(if (enabled) 1f else CofreBlock.DISABLED_ALPHA), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (kind == FieldKind.Secure) {
            BasicSecureTextField(
                state, fieldModifier, enabled = enabled, textStyle = style, interactionSource = interaction,
                cursorBrush = SolidColor(c.accent),
                decorator = { decorate(it) },
            )
        } else {
            BasicTextField(
                state, fieldModifier, enabled = enabled, textStyle = style, interactionSource = interaction,
                cursorBrush = SolidColor(c.accent),
                lineLimits = if (kind == FieldKind.Mono) TextFieldLineLimits.MultiLine() else TextFieldLineLimits.SingleLine,
                decorator = { decorate(it) },
            )
        }
        error?.let { CofreText(it, type.caption, color = c.danger) }
    }
}

/** "▾": no bundled face has it, so it's drawn like the icons. */
private val Caret = listOf("ccccc", ".ccc.", "..c..")

private val SearchGlyph = listOf(
    ".cccc...",
    "c....c..",
    "c....c..",
    "c....c..",
    "c....c..",
    ".cccc...",
    ".....cc.",
    "......cc",
)

/**
 * A block that opens a menu of [options]. The menu takes focus on the current choice; B or a tap outside
 * closes it. [compact] is the small trailing variant for rows ("0.16.14 ▾").
 */
@Composable
public fun <T> SelectButton(
    label: String,
    selection: T,
    onSelect: (T) -> Unit,
    options: List<Pair<T, String>>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val c = CofreTheme.colors
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selection }?.second ?: "—"
    Box(modifier) {
        if (compact) {
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .blockClickable { open = true }
                    .semantics { contentDescription = "$label, $current" },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CofreText(current, CofreTheme.type.label, color = c.muted)
                PixelArt(Caret, mapOf('c' to c.muted), pixel = 1.5.dp)
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .blockClickable { open = true }
                    .semantics { contentDescription = "$label, $current" }
                    .block(BlockKind.Button, lip = CofreBlock.buttonLip)
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CofreText(current, CofreTheme.type.title2, Modifier.weight(1f), maxLines = 1)
                PixelArt(Caret, mapOf('c' to c.muted), pixel = 2.dp)
            }
        }
        if (open) {
            BlockMenu(options, selection, onDismiss = { open = false }) {
                onSelect(it)
                open = false
            }
        }
    }
}

@Composable
private fun <T> BlockMenu(
    options: List<Pair<T, String>>,
    selection: T,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    val c = CofreTheme.colors
    Popup(
        offset = with(LocalDensity.current) { IntOffset(0, 4.dp.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        // The menu claims focus for its top item as it opens; then it moves to the current choice.
        val current = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos {}
            withFrameNanos {}
            runCatching { current.requestFocus() }
        }
        ScreenFocus(claim = true, Modifier.controllerInput(onBack = onDismiss)) {
            Column(
                Modifier
                    .width(260.dp)
                    .block(BlockKind.Raised)
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEach { (value, text) ->
                    val on = value == selection
                    Row(
                        Modifier
                            .then(if (on) Modifier.focusRequester(current) else Modifier)
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .blockClickable(role = Role.RadioButton) { onPick(value) }
                            .background(if (on) c.button else Color.Transparent)
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Radio(on)
                        CofreText(text, CofreTheme.type.rowTitle, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** A toggle chip. */
@Composable
public fun Chip(
    text: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChipFace(text, selected, modifier.toggleableChip(selected, onSelectedChange))
}

/** A single-select row of chips that scrolls sideways. */
@Composable
public fun <T> ChipGroup(
    selection: T,
    onSelect: (T) -> Unit,
    options: List<Pair<T, String>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        options.forEach { (value, text) ->
            val on = value == selection
            ChipFace(text, on, Modifier.focusRing().selectable(on, role = Role.RadioButton) { onSelect(value) })
        }
    }
}

private fun Modifier.toggleableChip(selected: Boolean, onChange: (Boolean) -> Unit): Modifier =
    focusRing().toggleable(selected, role = Role.Checkbox, onValueChange = onChange)

@Composable
private fun ChipFace(
    text: String,
    on: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .heightIn(min = 26.dp)
            .block(if (on) BlockKind.Primary else BlockKind.Button)
            .padding(horizontal = 9.dp)
            .padding(bottom = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        CofreText(
            text,
            CofreTheme.type.chip,
            color = if (on) CofreTheme.colors.onFace else CofreTheme.colors.inkSoft,
            maxLines = 1,
        )
    }
}
