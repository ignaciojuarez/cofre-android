package com.ignacio.cofre.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// DESIGN_SYSTEM §1.1. This is the only file that writes hex values. Cofre has one look: dark, with the purple accent.

/** Color tokens. [highContrast] swaps in the brighter outline and muted ink, like iOS's Increase Contrast. */
@Immutable
public class CofreColors(public val highContrast: Boolean = false) {
    public val page: Color = hex(0x0f0d17)
    public val side: Color = hex(0x0b0a11)
    public val panel: Color = hex(0x1a1727)
    public val raised: Color = hex(0x221e34)
    public val button: Color = hex(0x2b2641)
    public val buttonBevel: Color = hex(0x3d3560)
    public val well: Color = hex(0x0c0b12)
    public val bevel: Color = hex(0x342d52)
    public val lip: Color = hex(0x07060b)
    public val outline: Color = hex(if (highContrast) 0x8a82a3 else 0x05040a)
    public val ink: Color = hex(0xecedf3)
    public val inkSoft: Color = hex(0xc5c7d1)
    public val muted: Color = hex(if (highContrast) 0xc5c7d1 else 0xa29cb8)
    public val success: Color = hex(0x45c27a)
    public val warn: Color = hex(0xf0b84f)
    public val danger: Color = hex(0xf07a68)
    public val quilt: Color = hex(0x5ad1e8)
    public val accent: Color = hex(0x9a86ff)
    public val face: Color = hex(0x5b3fd6)
    public val faceBevel: Color = hex(0x8a74ff)
    public val faceLip: Color = hex(0x3a2694)
    public val onFace: Color = hex(0xffffff)

    /** `ink` at 8%. */
    public val hover: Color = hex(0xecedf3).copy(alpha = 0.08f)

    /** The solid color for a tone. */
    public fun tone(tone: Tone): Color = when (tone) {
        Tone.Neutral -> inkSoft
        Tone.Accent, Tone.Fabric -> accent
        Tone.Success -> success
        Tone.Warn, Tone.Forge -> warn
        Tone.Danger, Tone.NeoForge -> danger
        Tone.Quilt -> quilt
    }

    /** [tone] mixed into `panel` (or `well` for neutral). [amount] overrides the tone's default (banners use 0.16). */
    public fun tint(tone: Tone, amount: Float = tone.tintAmount): Color =
        if (tone == Tone.Neutral) well else mix(panel, tone(tone), amount)

    internal companion object {
        /** Stone texture cells behind every screen. */
        val stone: List<Color> = listOf(hex(0x0f0d17), hex(0x121020), hex(0x0d0b14), hex(0x151225))
    }
}

/** Status and loader tones. Tints are the tone mixed into `panel`. */
public enum class Tone(internal val tintAmount: Float) {
    Neutral(0f),
    Accent(0.22f),
    Success(0.24f),
    Warn(0.26f),
    Danger(0.26f),
    Fabric(0.22f),
    Forge(0.24f),
    NeoForge(0.24f),
    Quilt(0.22f),
}

/** Fixed colors used only inside pixel art (icons and scenes). UI uses [CofreColors]. */
internal object PixelArtColors {
    /** PixelScene's moonlit lake. The sky stays dark at the top so white titles read on any crop. */
    object Lake {
        val sky = intArrayOf(0x05071a, 0x0a0e28, 0x111638, 0x1a1c4a, 0x26245c, 0x342c6c)
        val moon = 0xf6f3ff to 0xc4bdea
        val halo = 0x2e2c6e to 0x4a4794
        val forest = listOf(0x232158 to 0x2e2b6c, 0x0d0b26 to 0x3a3882)
        const val WATER = 0x0b0f2c
        const val RIPPLE = 0x323c86
        val glint = intArrayOf(0xf6f3ff, 0xc9c5f6, 0x8a85d0, 0x5a56a8)
        val shore = 0x08081a to 0x1d3a2e
        val reed = 0x1c4532 to 0x6a4428
        val cabin = 0x0d0b22 to 0x2a2152
        val window = 0xffe08a to 0xf0b84f
        val glow = 0x4a3048 to 0x94603a
    }

    const val STAR_BRIGHT = 0xffffff
    const val STAR_DIM = 0x8c86c4
    const val CONTROLLER_GLYPH = 0x3a3550

    /** PixelIcon palettes (from the mockups' ICONS table). */
    val icons: Map<PixelGlyph, Map<Char, Int>> = mapOf(
        PixelGlyph.Grass to mapOf('G' to 0x6cbf3f, 'g' to 0x4f9a2e, 'd' to 0x7a5230, 'D' to 0x946640),
        PixelGlyph.Stone to mapOf('S' to 0x8b8b93, 's' to 0x6e6e78, 'd' to 0xa3a3ab),
        PixelGlyph.Ore to mapOf('S' to 0x8b8b93, 's' to 0x6e6e78),
        PixelGlyph.Book to mapOf('P' to 0x5b3fd6, 'p' to 0x8a74ff, 'Y' to 0xf0b84f, 'w' to 0xecedf3),
        PixelGlyph.Gear to mapOf('g' to 0x8f88a8, 'G' to 0xc5c7d1),
        PixelGlyph.Bolt to mapOf('Y' to 0xf0b84f, 'y' to 0xc98a1c),
        PixelGlyph.Cmd to mapOf('o' to 0xc98244, 'O' to 0x8a5226, 'g' to 0xb9bcc4, 'k' to 0x2b2b33),
        PixelGlyph.Key to mapOf('Y' to 0xf0b84f, 'y' to 0xb27a12),
        PixelGlyph.Shield to mapOf('S' to 0x45c27a, 's' to 0x2f8f57, 'W' to 0xecedf3),
        PixelGlyph.Check to mapOf('G' to 0x45c27a),
        PixelGlyph.Paper to mapOf('W' to 0xecedf3, 'l' to 0xa29cb8, 'F' to 0xc5c7d1),
        PixelGlyph.Chest to mapOf('B' to 0xa8642f, 'b' to 0x6e3d17, 'Y' to 0xf0b84f, 'k' to 0x2b1608),
        PixelGlyph.Head to mapOf(
            'H' to 0x3b2a1a, 'S' to 0xc99a74, 's' to 0xb0805a, 'E' to 0xecedf3, 'B' to 0x4a5bd6, 'M' to 0x7a4a3a,
        ),
        PixelGlyph.Cross to mapOf('R' to 0xf07a68),
        PixelGlyph.Dirt to mapOf('D' to 0x8a5a36, 'd' to 0x6a4326, 'l' to 0xa6764c),
        PixelGlyph.Cobble to mapOf('c' to 0xb0b0b6, 'C' to 0x88888f, 'k' to 0x55555c),
        PixelGlyph.Planks to mapOf('P' to 0xb88a50, 'p' to 0x8a6436, 'k' to 0x6e4c28, 'h' to 0xcfa266),
        PixelGlyph.Sand to mapOf('S' to 0xe2d29a, 's' to 0xcdb97e, 'l' to 0xf2e8bc),
        PixelGlyph.Gravel to mapOf('g' to 0x8c8580, 'G' to 0x6e6762, 'l' to 0xaea8a3, 'k' to 0x4e4846),
        PixelGlyph.Log to mapOf('b' to 0x5e4228, 'P' to 0xc09456, 'r' to 0x9a703e),
        PixelGlyph.Bricks to mapOf('R' to 0xb0563c, 'r' to 0x8c402c, 'm' to 0xcdc2b6),
        PixelGlyph.Fabric to mapOf(
            'o' to 0x38342a, 'h' to 0xf0e8d0, 'F' to 0xdccfae, 'f' to 0xbfb08c, 's' to 0x9c8f6c,
        ),
        PixelGlyph.Quilt to mapOf('P' to 0x9b4dff, 'M' to 0xd63fe8, 'B' to 0x35a9ff, 'b' to 0x4d5cff, 'V' to 0x8a3dff),
        PixelGlyph.Forge to mapOf('N' to 0x1f2d50, 'W' to 0xeef1f6, 'w' to 0xa9b3c4),
        PixelGlyph.NeoForge to mapOf(
            'E' to 0x6b3a22, 'e' to 0xa8603a, 'O' to 0xe86a2c, 'o' to 0xb84a1a, 'W' to 0xf6f0e6, 'k' to 0x2a1a14,
        ),
    )
}

/** Ore tints for instance icons and sample data. */
public enum class OreTint(public val color: Color) {
    Gold(hex(0xf0b84f)),
    Redstone(hex(0xf07a68)),
    Diamond(hex(0x5ad1e8)),
    Amethyst(hex(0xb07bff)),
    Emerald(hex(0x45c27a)),
    Copper(hex(0xf08a3a)),
    Lapis(hex(0x9a86ff)),
}

/** Straight sRGB mix, like iOS's `mixed(into:)`. Compose's `lerp` mixes in Oklab, which gives other values. */
internal fun mix(from: Color, to: Color, amount: Float): Color = Color(
    red = from.red + (to.red - from.red) * amount,
    green = from.green + (to.green - from.green) * amount,
    blue = from.blue + (to.blue - from.blue) * amount,
)

/** An opaque color from `0xRRGGBB`. */
internal fun hex(rgb: Int): Color = Color(0xff000000.toInt() or rgb)
