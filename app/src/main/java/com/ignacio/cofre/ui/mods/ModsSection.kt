package com.ignacio.cofre.ui.mods

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.PixelGlyph

/** Entry point of the Mods section. Owned by the screens work; replace this body. */
@Composable
fun ModsSection(graph: AppGraph, modifier: Modifier = Modifier) {
    EmptyState(title = "Mods", message = "Coming soon.", icon = PixelGlyph.Book, modifier = modifier)
}
