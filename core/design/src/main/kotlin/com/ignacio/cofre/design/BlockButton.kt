package com.ignacio.cofre.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

// §2 #2 BlockButton. One block recipe; roles swap fill, bevel, lip and ink.

public enum class ButtonRole { Primary, Secondary, Ghost, Danger, GhostDanger }

public enum class ButtonSize { Small, Regular, Large }

/**
 * Block button with an optional pixel icon. While [loading], a [Spinner] replaces the icon and the label stays.
 * Pressing drops the label 2 dp and shrinks the lip to 1 dp; it springs back in 90 ms.
 */
@Composable
public fun BlockButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: ButtonRole = ButtonRole.Secondary,
    size: ButtonSize = ButtonSize.Regular,
    icon: PixelGlyph? = null,
    iconTint: Color = OreTint.Diamond.color,
    fullWidth: Boolean = size != ButtonSize.Small,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val ink = when (role) {
        ButtonRole.Primary -> CofreTheme.colors.onFace
        ButtonRole.Secondary -> CofreTheme.colors.ink
        ButtonRole.Ghost -> CofreTheme.colors.muted
        ButtonRole.Danger, ButtonRole.GhostDanger -> CofreTheme.colors.danger
    }
    BlockButtonFrame(onClick, role, size, fullWidth, enabled, modifier) {
        val glyphSize = when (size) {
            ButtonSize.Small -> 11.dp
            ButtonSize.Regular -> 15.dp
            ButtonSize.Large -> 18.dp
        }
        when {
            loading -> Spinner(size = if (size == ButtonSize.Small) 11.dp else 14.dp)
            icon != null -> PixelIcon(icon, size = glyphSize, tint = iconTint, foreground = ink)
        }
        val type = CofreTheme.type
        val textRole = when (size) {
            ButtonSize.Small -> type.buttonS
            ButtonSize.Regular -> type.button
            ButtonSize.Large -> type.buttonL
        }
        CofreText(text, textRole, color = ink, maxLines = 1)
    }
}

/** Icon-only block button. [contentDescription] is what TalkBack reads. */
@Composable
public fun IconButton(
    glyph: PixelGlyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.Small,
    enabled: Boolean = true,
) {
    BlockButtonFrame(
        onClick, ButtonRole.Secondary, size, fullWidth = false, enabled = enabled,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
    ) {
        PixelIcon(glyph, size = if (size == ButtonSize.Small) 12.dp else 16.dp)
    }
}

@Composable
private fun BlockButtonFrame(
    onClick: () -> Unit,
    role: ButtonRole,
    size: ButtonSize,
    fullWidth: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState().value && enabled
    val hovered by interaction.collectIsHoveredAsState()
    val reduceMotion = CofreTheme.reduceMotion
    val ghost = role == ButtonRole.Ghost || role == ButtonRole.GhostDanger
    val restingLip = if (size == ButtonSize.Small) CofreBlock.smallButtonLip else CofreBlock.buttonLip
    val lipTarget = when {
        !enabled || ghost -> 0.dp
        pressed -> CofreBlock.pressedLip
        else -> restingLip
    }
    // Down instantly, up in 90 ms linear (§1.5).
    val spec = if (pressed) snap<Dp>() else tween(CofreMotion.PRESS, easing = LinearEasing)
    val lip by animateDpAsState(lipTarget, spec, label = "lip")
    val drop by animateDpAsState(if (pressed && !reduceMotion) CofreBlock.pressDrop else 0.dp, spec, label = "drop")
    val height = when {
        ghost -> 34.dp
        size == ButtonSize.Small -> 30.dp
        size == ButtonSize.Large -> 50.dp
        else -> 42.dp
    }
    val colors = CofreTheme.colors
    Row(
        modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = height)
            .blockClickable(interaction, enabled, onClick = onClick)
            .alpha(if (enabled) 1f else CofreBlock.DISABLED_ALPHA)
            .then(
                if (ghost) {
                    Modifier
                } else {
                    Modifier.block(if (role == ButtonRole.Primary) BlockKind.Primary else BlockKind.Button, lip, flat = !enabled)
                },
            )
            .innerOverlay(
                when {
                    pressed && reduceMotion -> Color.Black.copy(alpha = 0.18f)
                    hovered -> colors.hover
                    else -> Color.Transparent
                },
                inset = if (ghost) 0.dp else CofreBlock.outline,
            )
            .padding(horizontal = if (size == ButtonSize.Small) 10.dp else 14.dp)
            .padding(bottom = if (lipTarget > 1.dp) lipTarget - 1.dp else 0.dp)
            .offset { IntOffset(0, drop.roundToPx()) },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A flat color over the fill, inside the outline: hover, pressed rows, tints. */
internal fun Modifier.innerOverlay(color: Color, inset: Dp = CofreBlock.outline): Modifier =
    if (color == Color.Transparent) {
        this
    } else {
        drawWithContent {
            val i = inset.toPx()
            drawRect(color, Offset(i, i), Size(size.width - 2 * i, size.height - 2 * i))
            drawContent()
        }
    }
