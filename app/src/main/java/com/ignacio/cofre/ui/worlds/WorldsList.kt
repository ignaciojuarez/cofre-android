package com.ignacio.cofre.ui.worlds

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.ControllerAction
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.IconButton
import com.ignacio.cofre.design.ListSection
import com.ignacio.cofre.design.Pad
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.controllerActions
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.servers.MinecraftLaunchArguments
import com.ignacio.cofre.kit.worlds.World
import com.ignacio.cofre.launch.Launcher
import com.ignacio.cofre.ui.common.Fmt
import com.ignacio.cofre.ui.common.Note
import com.ignacio.cofre.ui.common.WorldIcon

/** An instance's worlds with quick play. [limit] shows only the most recent ones (Play's "Jump back in"). */
@Composable
fun WorldsList(
    graph: AppGraph,
    instance: Instance,
    modifier: Modifier = Modifier,
    limit: Int? = null,
) {
    val model = remember(instance.id) { WorldsModel(graph, instance) }
    val context = LocalContext.current
    var acting by rememberSaveable(instance.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(model) { model.refresh() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::import)
    }
    val play = { world: World ->
        graph.launcher.play(instance, Launcher.QuickPlay(MinecraftLaunchArguments.singleplayer(world.folder), world.name))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        model.error?.let { Banner(it, PixelGlyph.Cross, tone = Tone.Danger) { BlockButton(stringResource(R.string.dismiss), model::dismiss, size = ButtonSize.Small) } }
        model.notice?.let { Banner(it, PixelGlyph.Check, tone = Tone.Success) { BlockButton(stringResource(R.string.dismiss), model::dismiss, size = ButtonSize.Small) } }
        model.undo?.let { (name, _) ->
            Banner(stringResource(R.string.worlds_deleted, name), PixelGlyph.Check, tone = Tone.Success) {
                BlockButton(stringResource(R.string.undo), model::undoDelete, size = ButtonSize.Small)
            }
        }
        val shown = limit?.let { model.worlds.take(it) } ?: model.worlds
        when {
            !model.loaded -> Unit
            shown.isEmpty() && limit == null -> EmptyState(stringResource(R.string.worlds_empty_title), stringResource(R.string.worlds_empty_body), PixelGlyph.Grass)
            shown.isEmpty() -> Unit
            else -> ListSection(label = stringResource(if (limit != null) R.string.play_jump_back_in else R.string.instance_worlds)) {
                shown.forEach { world ->
                    WorldRow(
                        world,
                        onPlay = { play(world) },
                        onMore = if (limit == null) ({ acting = world.folder }) else null,
                        subtitle = listOfNotNull(world.mode, Fmt.ago(world.lastPlayed?.toEpochMilli()), Fmt.bytes(context, world.sizeBytes)).joinToString(" · "),
                    )
                }
            }
        }
        if (limit == null) {
            BlockButton(stringResource(R.string.worlds_import), { importer.launch(arrayOf("application/zip", "application/octet-stream")) }, icon = PixelGlyph.Chest)
            Note(stringResource(R.string.worlds_note))
        }
    }
    acting?.let { folder ->
        model.worlds.firstOrNull { it.folder == folder }?.let { world ->
            WorldActionsSheet(graph, model, world, onPlay = { play(world) }, onDismiss = { acting = null })
        }
    }
}

@Composable
private fun WorldRow(
    world: World,
    onPlay: () -> Unit,
    onMore: (() -> Unit)?,
    subtitle: String,
) {
    val more = stringResource(R.string.worlds_more)
    BlockRow(
        world.name,
        Modifier.controllerActions(*listOfNotNull(onMore?.let { ControllerAction(Pad.X, more, it) }).toTypedArray()),
        subtitle = subtitle,
        leading = { WorldIcon(world.iconPNG) },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BlockButton(stringResource(R.string.play), onPlay, role = ButtonRole.Primary, size = ButtonSize.Small, icon = PixelGlyph.Play)
                onMore?.let { IconButton(PixelGlyph.Gear, more, it) }
            }
        },
    )
}

/** Back up, restore, export, copy to another instance, delete: every world action in one sheet. */
@Composable
private fun WorldActionsSheet(
    graph: AppGraph,
    model: WorldsModel,
    world: World,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(0) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { model.export(world, it) }
        onDismiss()
    }
    BlockSheet(onDismiss) {
        SheetHeader(world.name, onDismiss)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (page) {
                0 -> {
                    BlockButton(stringResource(R.string.play), { onDismiss(); onPlay() }, role = ButtonRole.Primary, icon = PixelGlyph.Play)
                    BlockButton(stringResource(R.string.worlds_backup), { model.backup(world); onDismiss() })
                    BlockButton(stringResource(R.string.worlds_restore), { page = 1 })
                    BlockButton(stringResource(R.string.worlds_export), { exporter.launch("${world.folder}.zip") })
                    BlockButton(stringResource(R.string.worlds_copy), { page = 2 })
                    BlockButton(stringResource(R.string.delete), { model.delete(world); onDismiss() }, role = ButtonRole.GhostDanger)
                }
                1 -> {
                    val backups by produceState(emptyList<com.ignacio.cofre.kit.worlds.WorldBackup>(), world) { value = model.backups(world) }
                    if (backups.isEmpty()) Note(stringResource(R.string.worlds_no_backups))
                    ListSection(label = stringResource(R.string.worlds_backups)) {
                        backups.forEach { backup ->
                            BlockRow(
                                Fmt.ago(backup.date.toEpochMilli()).orEmpty(),
                                subtitle = Fmt.bytes(context, backup.sizeBytes),
                                onClick = { model.restore(world, backup); onDismiss() },
                            )
                        }
                    }
                    Note(stringResource(R.string.worlds_restore_note))
                    BlockButton(stringResource(R.string.back), { page = 0 })
                }
                else -> {
                    val others = graph.library.instances.filter { it.id != model.instance.id }
                    if (others.isEmpty()) Note(stringResource(R.string.worlds_no_other))
                    ListSection(label = stringResource(R.string.worlds_copy_to)) {
                        others.forEach { target ->
                            BlockRow(target.name, onClick = { model.copy(world, target); onDismiss() })
                        }
                    }
                    BlockButton(stringResource(R.string.back), { page = 0 })
                }
            }
        }
    }
}
