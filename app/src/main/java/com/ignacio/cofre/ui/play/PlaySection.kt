package com.ignacio.cofre.ui.play

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.PixelGlyph

/** Entry point of the Play section. Owned by the screens work; replace this body. */
@Composable
fun PlaySection(graph: AppGraph, modifier: Modifier = Modifier) {
    EmptyState(title = "Play", message = "Coming soon.", icon = PixelGlyph.Play, modifier = modifier)
}
