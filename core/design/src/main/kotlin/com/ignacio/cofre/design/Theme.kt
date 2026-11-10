package com.ignacio.cofre.design

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// DESIGN_SYSTEM §1.3–1.5 and the theme that hands every token down the tree (SwiftUI's @Environment).

/** Spacing scale and fixed sizes (§1.3). */
@Immutable
public object CofreSpacing {
    public val hit: Dp = 44.dp
    public val rowMin: Dp = 44.dp
    public val field: Dp = 38.dp
    public val sidebar: Dp = 248.dp
    public val rail: Dp = 76.dp
    public val readable: Dp = 720.dp
    public val card: Dp = 560.dp
    public val aside: Dp = 380.dp
}

/** Block geometry (§1.4). Square everywhere: corner radius is always 0. */
@Immutable
public object CofreBlock {
    public val outline: Dp = 2.dp
    public val bevel: Dp = 2.dp
    public val lip: Dp = 2.dp
    public val buttonLip: Dp = 4.dp
    public val smallButtonLip: Dp = 3.dp
    public val pressedLip: Dp = 1.dp
    public val pressDrop: Dp = 2.dp
    public val selectionBar: Dp = 4.dp
    public val focusRing: Dp = 2.dp
    public val focusOffset: Dp = 2.dp
    public const val DISABLED_ALPHA: Float = 0.42f
}

/** Motion durations in milliseconds (§1.5). */
@Immutable
public object CofreMotion {
    public const val PRESS: Int = 90
    public const val TOGGLE: Int = 120
}

/**
 * Width classes (§3.1), from the shell's measured width, never the device. Cofre is landscape only, so there's no
 * compact (phone portrait) class: a landscape phone is medium, the Odin 3 is expanded.
 */
public enum class WidthClass {
    Medium,
    Expanded,
    ;

    public companion object {
        /** Medium under 1100 dp, expanded from there. */
        public fun of(width: Dp): WidthClass = if (width < 1100.dp) Medium else Expanded
    }
}

@Suppress("ComposeCompositionLocalUsage")
internal val LocalCofreColors = staticCompositionLocalOf { CofreColors() }

@Suppress("ComposeCompositionLocalUsage")
internal val LocalCofreType = staticCompositionLocalOf { CofreType(bold = false) }

@Suppress("ComposeCompositionLocalUsage")
internal val LocalReduceMotion = staticCompositionLocalOf { false }

/** The width class of the container drawing this, set by [AdaptiveShell] and the other layouts. */
@Suppress("ComposeCompositionLocalUsage")
public val LocalWidthClass: ProvidableCompositionLocal<WidthClass> =
    compositionLocalOf { WidthClass.Medium }

/**
 * Cofre's theme. Dark only. Reads the system's high-contrast text, Bold Text and animation-scale settings unless
 * told otherwise.
 */
@Composable
public fun CofreTheme(
    highContrast: Boolean = systemHighContrast(),
    reduceMotion: Boolean = systemReduceMotion(),
    content: @Composable () -> Unit,
) {
    val bold = LocalConfiguration.current.let { Build.VERSION.SDK_INT >= 31 && it.fontWeightAdjustment > 0 }
    val colors = remember(highContrast) { CofreColors(highContrast) }
    val type = remember(bold) { CofreType(bold) }
    CompositionLocalProvider(
        LocalCofreColors provides colors,
        LocalCofreType provides type,
        LocalReduceMotion provides reduceMotion,
        content = content,
    )
}

/** Token access, like `MaterialTheme`: `CofreTheme.colors.accent`, `CofreTheme.type.body`. */
public object CofreTheme {
    public val colors: CofreColors
        @Composable @ReadOnlyComposable
        get() = LocalCofreColors.current

    /** Type roles (§1.2). */
    public val type: CofreType
        @Composable @ReadOnlyComposable
        get() = LocalCofreType.current

    public val widthClass: WidthClass
        @Composable @ReadOnlyComposable
        get() = LocalWidthClass.current

    /** True when the system's animator scale is 0 ("Remove animations"), iOS's Reduce Motion. */
    public val reduceMotion: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalReduceMotion.current
}

/** Text in a type role. Applies the role's uppercase transform. [color] defaults to `ink`. */
@Composable
public fun CofreText(
    text: String,
    role: TextRole,
    modifier: Modifier = Modifier,
    color: Color = CofreTheme.colors.ink,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    BasicText(
        text = if (role.uppercase) text.uppercase() else text,
        modifier = modifier,
        style = role.style.copy(color = color, textAlign = textAlign),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

@Composable
@ReadOnlyComposable
private fun systemHighContrast(): Boolean {
    if (Build.VERSION.SDK_INT < 34) return false
    val ui = LocalContext.current.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    return ui.contrast >= 0.5f
}

@Composable
@ReadOnlyComposable
private fun systemReduceMotion(): Boolean =
    Settings.Global.getFloat(LocalContext.current.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
