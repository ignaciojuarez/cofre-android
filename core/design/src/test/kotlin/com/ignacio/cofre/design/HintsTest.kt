package com.ignacio.cofre.design

import org.junit.Assert.assertEquals
import org.junit.Test

class HintsTest {
    @Test
    fun narrowHintBarDropsTrailingHintsInsteadOfWrapping() {
        val widths = listOf(90, 70, 100, 100, 160) // Select, Back, Filters, Search, LB RB Sections
        assertEquals(5, hintsThatFit(widths, gap = 14, max = 1100))
        assertEquals(4, hintsThatFit(widths, gap = 14, max = 402))
        assertEquals(3, hintsThatFit(widths, gap = 14, max = 401))
        assertEquals(0, hintsThatFit(widths, gap = 14, max = 80))
        assertEquals(0, hintsThatFit(emptyList(), gap = 14, max = 100))
    }
}
