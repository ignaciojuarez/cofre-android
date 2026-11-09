package com.ignacio.cofre.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.SettingsPane
import com.ignacio.cofre.design.Badge
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ColumnHeader
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.PaneScroll
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.SelectButton
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.Stat
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.WidthClass
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.ui.common.Fmt
import com.ignacio.cofre.ui.common.SceneHeader
import com.ignacio.cofre.ui.common.seed
import com.ignacio.cofre.ui.downloads.DownloadsStrip
import com.ignacio.cofre.ui.worlds.WorldsList

/** Play: the current instance and a big Play button, with recent worlds and servers beside it when there's room. */
@Composable
fun PlaySection(
    graph: AppGraph,
    modifier: Modifier = Modifier,
) {
    val library = graph.library
    LaunchedEffect(Unit) { library.refresh() }
    val active = library.active
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Short screens (a handheld in landscape) keep Play above the fold.
        val hero = (maxHeight * 0.3f).coerceIn(110.dp, 200.dp)
        if (maxWidth >= 760.dp && active != null) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ColumnHeader(stringResource(R.string.section_play)) { AccountChip(graph) }
                    PaneScroll { Home(graph, active, hero) }
                }
                Column(Modifier.width(360.dp).fillMaxHeight().background(CofreTheme.colors.side.copy(alpha = 0.55f))) {
                    ColumnHeader(stringResource(R.string.play_side_title))
                    PaneScroll { Side(graph, active) }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ColumnHeader(stringResource(R.string.section_play)) { AccountChip(graph) }
                PaneScroll {
                    Home(graph, active, hero)
                    active?.let { Side(graph, it) }
                }
            }
        }
    }
}

@Composable
private fun AccountChip(graph: AppGraph) {
    val entry = graph.accounts.selected
    BlockButton(
        entry?.account?.name ?: stringResource(R.string.accounts_sign_in),
        { graph.nav.openSettings(SettingsPane.Accounts) },
        size = ButtonSize.Small,
        icon = PixelGlyph.Head,
    )
}

@Composable
private fun Home(graph: AppGraph, active: Instance?, hero: Dp) {
    val library = graph.library
    val nav = graph.nav
    Banners(graph)
    when {
        !library.loaded -> BlockRow(stringResource(R.string.instances_loading), leading = { Spinner() })
        active == null -> EmptyState(stringResource(R.string.instances_empty_title), stringResource(R.string.instances_empty_body), PixelGlyph.Grass) {
            BlockButton(stringResource(R.string.instances_new_long), { nav.newInstance = true }, Modifier.testTag("play.new"), role = ButtonRole.Primary)
        }
        else -> {
            val compact = CofreTheme.widthClass == WidthClass.Compact
            SceneHeader(active.seed, hero) {
                CofreText(active.name, CofreTheme.type.display, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge("NeoForge ${active.loaderVersion ?: active.gameVersion}", tone = Tone.NeoForge)
                    Badge("Vulkan", tone = if (graph.vulkan.suitable) Tone.Success else Tone.Danger)
                }
            }
            val picker = @Composable { m: Modifier ->
                SelectButton(stringResource(R.string.play_instance), active.id, library::select, library.instances.map { it.id to it.name }, m)
            }
            val play = @Composable { m: Modifier ->
                BlockButton(stringResource(R.string.play), { graph.launcher.play(active) }, m.testTag("play.play"), role = ButtonRole.Primary, size = ButtonSize.Large, icon = PixelGlyph.Play)
            }
            if (compact) {
                play(Modifier)
                picker(Modifier)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    play(Modifier.weight(1f))
                    picker(Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat(Fmt.memory(graph.launcher.heapMB(active)), stringResource(R.string.play_memory), Modifier.weight(1f))
                Stat(Fmt.ago(active.lastPlayedAt) ?: stringResource(R.string.instance_never), stringResource(R.string.instance_played), Modifier.weight(1f))
                Stat("26.2", stringResource(R.string.play_version), Modifier.weight(1f))
            }
            BlockButton(stringResource(R.string.play_manage), { nav.openInstance(active.id) }, size = ButtonSize.Small, fullWidth = false)
        }
    }
}

/** Account, device, download and last-session notices, most urgent first. */
@Composable
private fun Banners(graph: AppGraph) {
    val nav = graph.nav
    if (!graph.vulkan.suitable) {
        Banner(stringResource(R.string.unsuitable_title), PixelGlyph.Cross, tone = Tone.Danger)
    }
    if (graph.library.loaded && !graph.accounts.ready) {
        val signedOut = graph.accounts.entries.any { it.signedOut }
        Banner(stringResource(if (signedOut) R.string.play_signed_out else R.string.play_sign_in), PixelGlyph.Head, tone = Tone.Warn) {
            BlockButton(stringResource(R.string.settings_accounts), { nav.openSettings(SettingsPane.Accounts) }, size = ButtonSize.Small)
        }
    }
    DownloadsStrip(graph.downloadsModel, onOpen = { nav.downloads = true })
}

@Composable
private fun Side(graph: AppGraph, active: Instance) {
    WorldsList(graph, active, limit = 4)
    ServersList(graph, active)
}
