package com.ignacio.cofre.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import com.ignacio.cofre.design.ControllerAction
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.GalleryItem
import com.ignacio.cofre.design.GalleryViewer
import com.ignacio.cofre.design.Pad
import com.ignacio.cofre.design.PaneScroll
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.PixelIcon
import com.ignacio.cofre.design.PixelScene
import com.ignacio.cofre.design.RemoteImage
import com.ignacio.cofre.design.SelectButton
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.controllerActions
import com.ignacio.cofre.design.cycle
import com.ignacio.cofre.design.outline
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.ui.common.Fmt
import com.ignacio.cofre.ui.common.Note
import com.ignacio.cofre.ui.common.WorldIcon
import com.ignacio.cofre.ui.common.loaderLabel
import com.ignacio.cofre.ui.common.seed
import com.ignacio.cofre.ui.downloads.DownloadsStrip
import java.io.File

/**
 * Play (Home, "A · Dashboard"): the instance's hero, Play and where you left off on the left; six tiles on the
 * right that each answer a question you have before launching. A opens a tile in place over the grid, B closes it
 * and focus goes back to that tile. Start plays from anywhere on Home.
 */
@Composable
fun PlaySection(
    graph: AppGraph,
    modifier: Modifier = Modifier,
) {
    val library = graph.library
    LaunchedEffect(Unit) { library.refresh() }
    val active = library.active
    val playHint = stringResource(R.string.play)
    val instanceHint = stringResource(R.string.play_instance)
    // Start plays (MainActivity's Start handler); Y switches to the next instance.
    val actions = buildList {
        if (active != null) add(ControllerAction(Pad.Start, playHint))
        if (active != null && library.instances.size > 1) add(ControllerAction(Pad.Y, instanceHint) { library.select(library.instances.cycle(active, 1).id) })
    }
    Column(modifier.fillMaxSize().controllerActions(*actions.toTypedArray())) {
        ColumnHeader(stringResource(R.string.section_play)) { AccountChip(graph) }
        when {
            !library.loaded -> PaneScroll { BlockRow(stringResource(R.string.instances_loading), leading = { Spinner() }) }
            active == null -> PaneScroll {
                DownloadsStrip(graph.downloadsModel, onOpen = { graph.nav.downloads = true })
                EmptyState(stringResource(R.string.instances_empty_title), stringResource(R.string.instances_empty_body), PixelGlyph.Grass) {
                    BlockButton(stringResource(R.string.instances_new_long), { graph.nav.newInstance = true }, Modifier.testTag("play.new"), role = ButtonRole.Primary)
                }
            }
            else -> Home(graph, active)
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

/** The six tiles, in grid order. */
enum class HomeTile { Ready, Folder, Performance, Server, Session, Screenshots }

@Composable
private fun Home(graph: AppGraph, active: Instance) {
    val model = remember(active.id) { HomeModel(graph, active) }
    val launcher = graph.launcher
    // Again after any library change (a link, a toggle elsewhere) and after a run ends.
    LaunchedEffect(model, graph.library.instances, launcher.run == null, launcher.stopped == null) { model.load() }
    var open by rememberSaveable(active.id) { mutableStateOf<HomeTile?>(null) }
    var returnTo by remember { mutableStateOf<HomeTile?>(null) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    val requesters = remember { HomeTile.entries.associateWith { FocusRequester() } }
    LaunchedEffect(returnTo) {
        val tile = returnTo ?: return@LaunchedEffect
        withFrameNanos {}
        runCatching { requesters.getValue(tile).requestFocus() }
        returnTo = null
    }
    val close = { open?.let { returnTo = it }; open = null }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = maxHeight
        // Shrinks on short screens so Play stays visible without scrolling.
        val hero = (height * 0.36f).coerceIn(64.dp, 230.dp)
        val tiles: @Composable (Dp) -> Unit = { height ->
            val tile = open
            if (tile == null) {
                TileGrid(graph, model, requesters, height, onOpen = { open = it })
            } else {
                TilePanelFor(tile, graph, model, onClose = close, onView = { viewing = it }, modifier = Modifier.fillMaxWidth().height(height))
            }
        }
        // Always two columns (landscape only): Play stays above the fold down to about 360 dp tall, the tiles scroll.
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(0.44f).fillMaxHeight()) { PaneScroll { Left(graph, model, hero) } }
            Box(Modifier.weight(0.56f).fillMaxHeight().padding(start = 4.dp, end = 20.dp, top = 6.dp, bottom = 12.dp)) {
                // Three rows that fill the column, or scroll when that would make tiles too short.
                val rows = ((height - 36.dp) / 3).coerceAtLeast(TILE_MIN)
                if (open == null) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { tiles(rows) }
                } else {
                    tiles(height - 18.dp)
                }
            }
        }
    }
    viewing?.let { start ->
        GalleryViewer(model.screenshots.map { it.galleryItem() }, start, onClose = { viewing = null })
    }
}

private val TILE_MIN = 118.dp

@Composable
private fun ColumnScope.Left(graph: AppGraph, model: HomeModel, heroHeight: Dp) {
    val active = model.instance
    val library = graph.library
    DownloadsStrip(graph.downloadsModel, onOpen = { graph.nav.downloads = true })
    (model.error ?: library.error)?.let { Banner(it, PixelGlyph.Cross, tone = Tone.Danger) }
    if (model.running) Banner(stringResource(R.string.home_running), PixelGlyph.Play, Modifier.testTag("play.running"), tone = Tone.Accent)
    Hero(graph, model, heroHeight)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (model.running) {
            BlockButton(stringResource(R.string.home_running_short), {}, Modifier.weight(1f), size = ButtonSize.Large, icon = PixelGlyph.Play, enabled = false)
        } else {
            BlockButton(stringResource(R.string.play), { model.play() }, Modifier.weight(1f).testTag("play.play"), role = ButtonRole.Primary, size = ButtonSize.Large, icon = PixelGlyph.Play)
        }
        SelectButton(stringResource(R.string.play_instance), active.id, library::select, library.instances.map { it.id to it.name }, Modifier.weight(1f))
    }
    if (model.running) Note(stringResource(R.string.home_running_note))
    // Each row is one focus target: A plays straight into that world or server.
    model.lastWorld?.let { world ->
        BlockRow(
            stringResource(R.string.home_resume, world.name),
            Modifier.testTag("play.resume"),
            subtitle = listOfNotNull(world.mode, Fmt.ago(world.lastPlayed?.toEpochMilli())).joinToString(" · "),
            dimmed = model.running,
            onClick = { model.resume(world) }.takeUnless { model.running },
            leading = { WorldIcon(world.iconPNG) },
            trailing = { PixelIcon(PixelGlyph.Play, size = 14.dp) },
        )
    }
    model.server?.let { server ->
        val ping = model.servers.pings[server.id]
        BlockRow(
            stringResource(R.string.home_join, server.record.name),
            Modifier.testTag("play.join"),
            subtitle = pingText(ping) ?: server.record.ip,
            dimmed = model.running || server.address == null,
            onClick = { model.join(server) }.takeUnless { model.running || server.address == null },
            leading = { WorldIcon((ping as? ServersModel.Ping.Online)?.status?.faviconPNG ?: server.record.iconPNG) },
            trailing = { PixelIcon(PixelGlyph.Play, size = 14.dp) },
        )
    }
}

@Composable
private fun Hero(graph: AppGraph, model: HomeModel, height: Dp) {
    val active = model.instance
    val cover = model.screenshots.firstOrNull()
    Box(Modifier.fillMaxWidth().height(height).clipToBounds().outline()) {
        RemoteImage(
            cover?.uri.orEmpty(),
            Modifier.fillMaxSize(),
            ContentScale.Crop,
            placeholder = { PixelScene(active.seed, Modifier.fillMaxSize(), width = 240, height = 110) },
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CofreText(active.name, CofreTheme.type.display, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(active.loaderLabel, tone = Tone.NeoForge)
                Badge("Vulkan", tone = if (graph.vulkan.suitable) Tone.Success else Tone.Danger)
                model.folder?.folder?.let { Badge(it.name, tone = Tone.Accent) }
            }
        }
    }
}

/** A file as a URL the image loader reads. */
internal val File.uri: String get() = toURI().toString()

internal fun File.galleryItem(): GalleryItem = GalleryItem(uri, name, full = uri, title = name)
