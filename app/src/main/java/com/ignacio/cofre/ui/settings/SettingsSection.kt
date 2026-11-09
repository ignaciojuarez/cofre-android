package com.ignacio.cofre.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.PixelGlyph

/** Entry point of the Settings section. Owned by the screens work; replace this body. */
@Composable
fun SettingsSection(graph: AppGraph, modifier: Modifier = Modifier) {
    EmptyState(title = "Settings", message = "Coming soon.", icon = PixelGlyph.Gear, modifier = modifier)
}
