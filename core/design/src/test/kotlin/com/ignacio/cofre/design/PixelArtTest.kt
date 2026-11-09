package com.ignacio.cofre.design

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The scene and stone texture must match iOS pixel for pixel; checksums come from running the Swift code. */
class PixelArtTest {
    private fun fnv(px: IntArray): UInt = px.fold(2166136261u) { h, p -> (h xor p.toUInt()) * 16777619u }

    @Test
    fun sceneMatchesIos() {
        val golden = listOf(
            Triple(3, 120, 80) to 1995306337u,
            Triple(7, 240, 110) to 1485035841u,
            Triple(11, 200, 140) to 2725756635u,
            Triple(23, 160, 60) to 3514391870u,
            Triple(42, 24, 12) to 2347585612u,
            Triple(5, 130, 66) to 1467458713u,
        )
        for ((size, sum) in golden) {
            val (seed, w, h) = size
            val px = SceneArt.render(seed, w, h)
            assertFalse("$size left unpainted pixels", px.contains(0))
            assertEquals("seed $seed at ${w}x$h", sum, fnv(px))
        }
    }

    @Test
    fun stoneTextureMatchesIos() {
        val cells = StoneTexture.cells.map { it.toArgb() and 0xffffff }.toIntArray()
        assertEquals(1049953799u, fnv(cells))
    }

    @Test
    fun everyGlyphHasItsPalette() {
        for (glyph in PixelGlyph.entries) {
            val palette = PixelArtColors.icons[glyph].orEmpty().keys + setOf('.', '@', '&', 'c')
            val used = glyph.rows.joinToString("").toSet()
            assertEquals("$glyph uses chars outside its palette", emptySet<Char>(), used - palette)
        }
        assertEquals(26, PixelGlyph.entries.size)
    }
}
