package com.ignacio.cofre.ui.instances

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.PixelGlyph

/** Entry point of the Instances section. Owned by the screens work; replace this body. */
@Composable
fun InstancesSection(graph: AppGraph, modifier: Modifier = Modifier) {
    EmptyState(title = "Instances", message = "Coming soon.", icon = PixelGlyph.Grass, modifier = modifier)
}
