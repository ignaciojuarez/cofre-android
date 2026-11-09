package com.ignacio.cofre.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// §2 #16 PixelIcon: 8×8 glyphs drawn from row strings (same art as the mockups; loaders are 12×12). Decorative.

/**
 * Pixel glyphs. Row strings use one char per pixel: "." is transparent, "@" the tint, "&" the tint shaded to 62%,
 * "c" the foreground color; any other char comes from the glyph's palette.
 */
public enum class PixelGlyph(internal val rows: List<String>) {
    Grass(listOf("GGGGGGGG", "GgGGGgGG", "gGdGgGdg", "dDdDDdDd", "DdDDdDDD", "DDdDDDdD", "dDDDdDDd", "DDdDDDDD")),
    Stone(listOf("SsSSdSSs", "SSsSSSsS", "dSSsSdSS", "SSSSsSSs", "SsdSSSsS", "sSSSsSdS", "SSsSSSSs", "SdSSsSSS")),
    Ore(listOf("ssSsssSs", "s@@ssSss", "s@&sss@s", "ssssS@@s", "Sssss&@s", "s@@sssss", "s@&ssSss", "ssssssss")),
    Book(listOf(".PPPPPP.", "PppppppP", "PpYYYppP", "PppppppP", "PpYYpppP", "PppppppP", "PPPPPPPP", ".wwwwww.")),
    Gear(listOf("...gg...", ".g.GG.g.", "..GGGG..", "gGG..GGg", "gGG..GGg", "..GGGG..", ".g.GG.g.", "...gg...")),
    Play(listOf(".c......", ".cc.....", ".ccc....", ".cccc...", ".cccc...", ".ccc....", ".cc.....", ".c......")),
    Bolt(listOf("....YYY.", "...YYY..", "..YYY...", ".YYYYYY.", "...YYY..", "..YYy...", ".YYy....", "Yy......")),
    Cmd(listOf("OoOoOoOo", "oOOOOOOO", "OOggggOo", "oOgkkgOO", "OOgkkgOo", "oOggggOO", "OOOOOOOo", "oOoOoOoO")),
    Key(listOf("..........", ".YYY......", "Y.y.Y.....", "Yy.yYYYYYY", "Y.y.Y..Y.Y", ".YYY......", "..........")),
    Shield(listOf("SSSSSSSS", "SssssssS", "SsssssWS", "SssssWsS", "SWsssWsS", "SsWsWssS", ".SsWssS.", "..SSSS..")),
    Check(listOf("......G", ".....GG", "G...GG.", "GG.GG..", ".GGG...", "..G....")),
    Paper(listOf("WWWWW..", "WWWWWF.", "WllllWF", "WWWWWWW", "WllllWW", "WWWWWWW", "WlllWWW", "WWWWWWW")),
    Chest(listOf("bbbbbbbb", "bBBBBBBb", "bBBBBBBb", "bbbYYbbb", "kkkYYkkk", "bBBBBBBb", "bBBBBBBb", "bbbbbbbb")),
    Head(listOf("HHHHHHHH", "HHHHHHHH", "HSSSSSSH", "SEBSSBES", "SSSssSSS", "SSsMMsSS", "SSSSSSSS", "SSSSSSSS")),
    Cross(listOf("R.....R", ".R...R.", "..R.R..", "...R...", "..R.R..", ".R...R.", "R.....R")),

    // Blocks for vanilla instance icons. Our own art, not Mojang's textures.
    Dirt(listOf("DdDDlDDd", "DDDdDDDD", "lDDDDdDD", "DDdDDDDl", "DDDDlDdD", "dDlDDDDD", "DDDDdDDl", "DlDDDDdD")),
    Cobble(listOf("CcCkCCck", "CCkkcCCk", "kkCCkkkk", "cCCCCkcC", "CCCckkCC", "kkkkCCck", "CckCCCCk", "CCkkCckk")),
    Planks(listOf("PPPPPPkP", "PhPPPPkP", "PPPPPPkp", "pppppppp", "PPkPPPPP", "PPkPPhPP", "PPkPPPPP", "pppppppp")),
    Sand(listOf("SSsSSSlS", "SlSSSsSS", "SSSSlSSs", "sSSSSSSS", "SSlSSsSl", "SSSSSSSS", "SsSSlSSS", "SSSsSSsS")),
    Gravel(listOf("gGgkglGg", "lgGgGgkg", "GkglgGgl", "gGgGkgGg", "kglgGlgG", "gGgkgGkg", "lgGglgGl", "gkGgGkgg")),
    Log(listOf("bbbbbbbb", "bPPPPPPb", "bPrrrrPb", "bPrPPrPb", "bPrPPrPb", "bPrrrrPb", "bPPPPPPb", "bbbbbbbb")),
    Bricks(listOf("RRRmRRRm", "rrrmrrrm", "mmmmmmmm", "RmRRRmRR", "rmrrrmrr", "mmmmmmmm", "RRRmRRRm", "rrrmrrrm")),

    // Loaders: our own pixel redraws of each project's mark, 12×12 so they read at 16 dp.
    Fabric(
        listOf(
            "......oo....", ".....ohhoo..", "....ohFFhso.", "...ohFFFFsso", "..ohFFFFFfso", ".ohFFFFFffo.",
            "ohFFFFFffo..", "oFFFFFffo...", "oFFFFffo....", "oFFfffo.....", ".offfo......", "..ooo.......",
        ),
    ),
    Quilt(
        listOf(
            "PPP.MMM.BBB.", "PPP.MMM.BBB.", "PPP.MMM.BBB.", "............", "MMM.BBB.bbb.", "MMM.BBB.bbb.",
            "MMM.BBB.bbb.", "............", "VVV.bbb..VV.", "VVV.bbb.VVVV", "VVV.bbb.VVVV", ".........VV.",
        ),
    ),
    Forge(
        listOf(
            "NNNNNNNNNNNN", "NNNNNNNNNNNN", "NNNWWWWWWWWN", "NWWWWWWWWWWN", "NNWWWWWWWWwN", "NNNNNWWWwNNN",
            "NNNNNWWWwNNN", "NNNNWWWWWwNN", "NNNWWWWWWWwN", "NNNWWNNNWWwN", "NNNNNNNNNNNN", "NNNNNNNNNNNN",
        ),
    ),
    NeoForge(
        listOf(
            ".EE......EE.", ".EeE....EeE.", ".EeOOOOOOeE.", ".OOOOOOOOOO.", "OOOOOOOOOOOO", "OOkWOOOOWkOO",
            "OOkkOOOOkkOO", "oOOOOWWOOOOo", ".oOWWWWWWOo.", "..oWWkkWWo..", "...WWWWWW...", "....WkkW....",
        ),
    ),
}

/** A pixel glyph, fitted and centered in a [size] square. [tint] colors ore veins; [foreground] the play arrow. */
@Composable
public fun PixelIcon(
    glyph: PixelGlyph,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = OreTint.Diamond.color,
    foreground: Color = CofreTheme.colors.ink,
) {
    val palette = PixelArtColors.icons[glyph].orEmpty()
    Canvas(modifier.size(size)) {
        val shade = tint.copy(red = tint.red * 0.62f, green = tint.green * 0.62f, blue = tint.blue * 0.62f)
        drawPixels(glyph.rows) { ch ->
            when (ch) {
                '@' -> tint
                '&' -> shade
                'c' -> foreground
                else -> palette[ch]?.let(::hex)
            }
        }
    }
}

/**
 * Larger art in the PixelIcon recipe: one char per pixel, "." transparent. Each art pixel is [pixel] dp, so art
 * lines up with a [PixelScene] drawn at the same scale.
 */
@Composable
public fun PixelArt(
    rows: List<String>,
    palette: Map<Char, Color>,
    modifier: Modifier = Modifier,
    pixel: Dp = 4.dp,
) {
    val w = rows.maxOfOrNull { it.length } ?: 0
    Canvas(modifier.size(pixel * w, pixel * rows.size)) { drawPixels(rows) { palette[it] } }
}

/** Fits the grid into the canvas, centered, and fills each pixel whose char [colorOf] maps to a color. */
internal fun DrawScope.drawPixels(rows: List<String>, colorOf: (Char) -> Color?) {
    val h = rows.size
    val w = rows.maxOfOrNull { it.length } ?: 1
    val k = minOf(size.width / w, size.height / h)
    val ox = (size.width - k * w) / 2
    val oy = (size.height - k * h) / 2
    rows.forEachIndexed { y, row ->
        row.forEachIndexed { x, ch ->
            if (ch != '.') {
                colorOf(ch)?.let { drawRect(it, Offset(ox + x * k, oy + y * k), Size(k + 0.01f, k + 0.01f)) }
            }
        }
    }
}
