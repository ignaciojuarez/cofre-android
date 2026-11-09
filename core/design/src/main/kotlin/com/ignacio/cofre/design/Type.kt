package com.ignacio.cofre.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

// DESIGN_SYSTEM §1.2. Fonts ship in res/font (SIL OFL 1.1, see res/raw/ofl.txt and NOTICE).

/** A type role: its style, plus the uppercase transform `TextStyle` can't carry. Draw it with [CofreText]. */
@Immutable
public data class TextRole(val style: TextStyle, val uppercase: Boolean = false)

/**
 * Every type role, sized in sp so it follows the system font scale (iOS's Dynamic Type). [bold] swaps in the
 * heavier face where a role has one, for Android's Bold Text setting.
 */
@Immutable
public class CofreType internal constructor(bold: Boolean, compact: Boolean) {
    public val display: TextRole = role(Pixelify, BOLD, if (compact) 31 else 34)
    public val title: TextRole = role(Pixelify, SEMI, 26, lineSpacing = 2f)
    public val title2: TextRole = role(Pixelify, SEMI, 21)
    public val header: TextRole = role(Pixelify, SEMI, 22)
    public val headerCompact: TextRole = role(Pixelify, SEMI, 17)
    public val buttonL: TextRole = role(Pixelify, MEDIUM, 19)
    public val button: TextRole = role(Pixelify, MEDIUM, 15)
    public val buttonS: TextRole = role(Pixelify, MEDIUM, 13)
    public val chip: TextRole = role(Pixelify, MEDIUM, 12)
    public val nav: TextRole = role(Pixelify, MEDIUM, 16)
    public val navSmall: TextRole = role(Pixelify, MEDIUM, 11.5f)
    public val stat: TextRole = role(Pixelify, BOLD, 20)
    public val badge: TextRole = role(Pixelify, MEDIUM, 11, tracking = 0.33f, uppercase = true)
    public val body: TextRole = role(Geist, if (bold) MEDIUM else REGULAR, 15, lineSpacing = 8f)
    public val rowTitle: TextRole = role(Geist, if (bold) SEMI else MEDIUM, 14)
    public val secondary: TextRole = role(Geist, if (bold) MEDIUM else REGULAR, 13, lineSpacing = 6f)
    public val caption: TextRole = role(Geist, if (bold) MEDIUM else REGULAR, 11)
    public val label: TextRole = role(GeistMono, if (bold) MEDIUM else REGULAR, 11, tracking = 0.77f, uppercase = true)
    public val code: TextRole = role(GeistMono, if (bold) MEDIUM else REGULAR, 11, lineSpacing = 7f)

    internal companion object {
        private val REGULAR = FontWeight.Normal
        private val MEDIUM = FontWeight.Medium
        private val SEMI = FontWeight.SemiBold
        private val BOLD = FontWeight.Bold

        val Pixelify = FontFamily(
            Font(R.font.pixelify_sans_medium, MEDIUM),
            Font(R.font.pixelify_sans_semibold, SEMI),
            Font(R.font.pixelify_sans_bold, BOLD),
        )
        val Geist = FontFamily(
            Font(R.font.geist_regular, REGULAR),
            Font(R.font.geist_medium, MEDIUM),
            Font(R.font.geist_semibold, SEMI),
        )
        val GeistMono = FontFamily(
            Font(R.font.geist_mono_regular, REGULAR),
            Font(R.font.geist_mono_medium, MEDIUM),
        )

        /** Each family's natural line height (hhea ascent − descent), so iOS's extra line spacing adds on top. */
        private fun naturalLineHeight(family: FontFamily) = if (family == Pixelify) 1.2f else 1.3f

        private fun role(
            family: FontFamily,
            weight: FontWeight,
            size: Number,
            tracking: Float = 0f,
            lineSpacing: Float = 0f,
            uppercase: Boolean = false,
        ): TextRole {
            val px = size.toFloat()
            return TextRole(
                TextStyle(
                    fontFamily = family,
                    fontWeight = weight,
                    fontSize = px.sp,
                    letterSpacing = tracking.sp,
                    lineHeight = if (lineSpacing > 0) (px * naturalLineHeight(family) + lineSpacing).sp else TextUnit.Unspecified,
                    // Pixelify's "fi" ligature breaks the pixel grid; the mockups turn these off everywhere.
                    fontFeatureSettings = "liga 0, clig 0, calt 0",
                ),
                uppercase,
            )
        }
    }
}
