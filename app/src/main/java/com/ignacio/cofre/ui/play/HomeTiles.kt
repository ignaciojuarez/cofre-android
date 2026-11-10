package com.ignacio.cofre.ui.play

import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.SettingsPane
import com.ignacio.cofre.design.Badge
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockKind
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.BlockSlider
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.ControllerAction
import com.ignacio.cofre.design.DashTile
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.GalleryGrid
import com.ignacio.cofre.design.ListSection
import com.ignacio.cofre.design.LogLevel
import com.ignacio.cofre.design.LogLine
import com.ignacio.cofre.design.LogView
import com.ignacio.cofre.design.Pad
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.PixelIcon
import com.ignacio.cofre.design.PixelScene
import com.ignacio.cofre.design.RemoteImage
import com.ignacio.cofre.design.SectionLabel
import com.ignacio.cofre.design.Segmented
import com.ignacio.cofre.design.SelectButton
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.Stat
import com.ignacio.cofre.design.TilePanel
import com.ignacio.cofre.design.ToggleRow
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.block
import com.ignacio.cofre.design.controllerActions
import com.ignacio.cofre.design.outline
import com.ignacio.cofre.kit.logs.GameExitReport
import com.ignacio.cofre.launch.Launcher
import com.ignacio.cofre.ui.common.Fmt
import com.ignacio.cofre.ui.common.Note
import com.ignacio.cofre.ui.setup.plainVulkan
import kotlin.math.roundToInt

// Home's six tiles: each a summary ([TileGrid]) that opens to a panel in its place ([TilePanelFor]).

@Composable
internal fun TileGrid(
    graph: AppGraph,
    model: HomeModel,
    requesters: Map<HomeTile, FocusRequester>,
    rowHeight: Dp,
    onOpen: (HomeTile) -> Unit,
) {
    val open = stringResource(R.string.home_open)
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        HomeTile.entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(rowHeight), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                pair.forEach { tile ->
                    val modifier = Modifier.weight(1f).fillMaxSize()
                        .focusRequester(requesters.getValue(tile))
                        .controllerActions(ControllerAction(Pad.Confirm, open))
                        .testTag("home.tile.${tile.name.lowercase()}")
                    val onClick = { onOpen(tile) }
                    when (tile) {
                        HomeTile.Ready -> ReadyTile(model, onClick, modifier)
                        HomeTile.Folder -> FolderTile(model, onClick, modifier)
                        HomeTile.Performance -> PerformanceTile(graph, model, onClick, modifier)
                        HomeTile.Server -> ServerTile(model, onClick, modifier)
                        HomeTile.Session -> SessionTile(model, onClick, modifier)
                        HomeTile.Screenshots -> ScreenshotsTile(model, onClick, modifier)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TilePanelFor(
    tile: HomeTile,
    graph: AppGraph,
    model: HomeModel,
    onClose: () -> Unit,
    onView: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, icon) = when (tile) {
        HomeTile.Ready -> stringResource(R.string.home_ready) to PixelGlyph.Shield
        HomeTile.Folder -> stringResource(R.string.home_folder) to PixelGlyph.Chest
        HomeTile.Performance -> stringResource(R.string.home_performance) to PixelGlyph.Bolt
        HomeTile.Server -> stringResource(R.string.servers_title) to PixelGlyph.Grass
        HomeTile.Session -> stringResource(R.string.home_session) to PixelGlyph.Book
        HomeTile.Screenshots -> stringResource(R.string.home_screenshots) to PixelGlyph.Paper
    }
    TilePanel(title, icon, stringResource(R.string.home_back), onClose, modifier.testTag("home.panel.${tile.name.lowercase()}")) {
        when (tile) {
            HomeTile.Ready -> ReadyPanel(graph, model)
            HomeTile.Folder -> FolderPanel(graph, model)
            HomeTile.Performance -> PerformancePanel(graph, model)
            HomeTile.Server -> ServersList(graph, model.instance, model = model.servers)
            HomeTile.Session -> SessionPanel(model)
            HomeTile.Screenshots -> ScreenshotsPanel(model, onView)
        }
    }
}

// 1 · Ready check

@Composable
private fun ReadyTile(model: HomeModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val check = model.ready
    val problems = check?.let { readyProblems(it) }.orEmpty()
    val tone = when {
        check == null -> Tone.Neutral
        check.blockers > 0 -> Tone.Danger
        check.toFix > 0 -> Tone.Warn
        else -> Tone.Success
    }
    val value = when {
        check == null -> stringResource(R.string.home_checking)
        check.blockers > 0 -> stringResource(R.string.home_cant_launch)
        check.toFix > 0 -> pluralStringResource(R.plurals.home_to_fix, check.toFix, check.toFix)
        else -> stringResource(R.string.home_ready_value)
    }
    val subtitle = when {
        problems.isNotEmpty() -> problems.joinToString(" · ")
        check != null && check.firstPlayMB > 0 -> stringResource(R.string.home_first_play, check.firstPlayMB)
        check != null -> stringResource(R.string.home_ready_all)
        else -> null
    }
    DashTile(stringResource(R.string.home_ready), PixelGlyph.Shield, value, onClick, modifier, subtitle = subtitle, tone = tone) {
        when {
            check == null -> Unit
            check.account != Launcher.AccountState.Ready -> Badge(stringResource(R.string.accounts_sign_in), tone = Tone.Danger)
            check.modProblems -> Badge(stringResource(R.string.home_fix_all), tone = Tone.Warn)
            check.blockers > 0 -> Unit // Vulkan: the value already says it won't launch; never "All good"
            check.firstPlayMB > 0 -> Badge(stringResource(R.string.home_first_play_badge), tone = Tone.Accent)
            else -> Badge(stringResource(R.string.home_good_to_go), tone = Tone.Success)
        }
    }
}

/** One short line per problem, most serious first. */
@Composable
private fun readyProblems(check: Launcher.ReadyCheck): List<String> = buildList {
    when (check.account) {
        Launcher.AccountState.None -> add(stringResource(R.string.home_no_account))
        Launcher.AccountState.SignedOut -> add(stringResource(R.string.home_signed_out_short))
        Launcher.AccountState.Ready -> Unit
    }
    if (!check.vulkanOk) add(stringResource(R.string.home_vulkan_missing_short))
    check.folderProblem?.let(::add)
    check.issues.forEach { add(stringResource(R.string.home_may_crash, it.name)) }
    addAll(check.lines)
}

@Composable
private fun ReadyPanel(graph: AppGraph, model: HomeModel) {
    val check = model.ready ?: return Note(stringResource(R.string.home_checking))
    val resources = LocalResources.current
    val busy = model.fixing != null
    when (check.account) {
        Launcher.AccountState.Ready -> Line(true, stringResource(R.string.home_signed_in, check.accountName.orEmpty()), stringResource(R.string.home_account_ok))
        else -> Line(
            false,
            stringResource(if (check.account == Launcher.AccountState.SignedOut) R.string.home_signed_out_short else R.string.home_no_account),
            stringResource(R.string.play_sign_in),
            danger = true,
        ) {
            BlockButton(stringResource(R.string.accounts_sign_in), { graph.nav.openSettings(SettingsPane.Accounts) }, Modifier.testTag("home.signIn"), role = ButtonRole.Primary, size = ButtonSize.Small)
        }
    }
    if (check.vulkanOk) {
        Line(true, stringResource(R.string.home_vulkan_ok), check.vulkan.deviceName)
    } else {
        Line(false, stringResource(R.string.unsuitable_title), check.vulkan.missing.joinToString { plainVulkan(resources, it) }, danger = true)
    }
    when {
        check.firstPlayMB > 0 -> Line(null, stringResource(R.string.home_java_loader, check.loaderVersion), stringResource(R.string.home_first_play, check.firstPlayMB))
        else -> Line(true, stringResource(R.string.home_java_loader, check.loaderVersion), stringResource(R.string.home_installed))
    }
    check.folderProblem?.let { problem ->
        Line(false, stringResource(R.string.home_folder_cant_play), problem, danger = true) {
            BlockButton(stringResource(R.string.home_open_instance), { graph.nav.openInstance(check.instance.id) }, size = ButtonSize.Small)
        }
    }
    check.issues.forEach { issue ->
        val key = issue.file ?: issue.id
        Line(false, stringResource(R.string.home_may_crash, issue.name), issue.reason) {
            BlockButton(stringResource(R.string.home_turn_off), { model.turnOff(issue) }, size = ButtonSize.Small, loading = model.fixing == key, enabled = !busy)
        }
    }
    check.missing.forEachIndexed { i, requirement ->
        Line(false, check.lines.getOrElse(i) { requirement.name }, stringResource(R.string.home_from_modrinth)) {
            BlockButton(stringResource(R.string.home_get, requirement.name), { model.getMod(requirement) }, size = ButtonSize.Small, loading = model.fixing == requirement.modId, enabled = !busy)
        }
    }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (check.modProblems) {
            BlockButton(
                stringResource(R.string.home_fix_all), model::fixAll, Modifier.weight(1f).testTag("home.fixAll"),
                role = ButtonRole.Primary, loading = model.fixing == HomeModel.FIX_ALL, enabled = !busy,
            )
            BlockButton(stringResource(R.string.preflight_play_anyway), { model.play(checked = false) }, Modifier.weight(1f), icon = PixelGlyph.Play, enabled = !busy && !model.running)
        } else {
            BlockButton(stringResource(R.string.play), { model.play() }, Modifier.weight(1f), role = ButtonRole.Primary, icon = PixelGlyph.Play, enabled = !model.running)
        }
    }
}

/** A ready-check line: [ok] true ✓, false ✗ (or ! when not [danger]), null "will download". */
@Composable
private fun Line(
    ok: Boolean?,
    title: String,
    subtitle: String?,
    danger: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    BlockRow(
        title,
        subtitle = subtitle,
        leading = {
            when (ok) {
                true -> PixelIcon(PixelGlyph.Check, size = 16.dp)
                false -> if (danger) PixelIcon(PixelGlyph.Cross, size = 16.dp) else Badge("!", tone = Tone.Warn)
                null -> PixelIcon(PixelGlyph.Chest, size = 16.dp)
            }
        },
        trailing = action,
    )
}

// 2 · Mod folder

@Composable
private fun FolderTile(model: HomeModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val info = model.folder
    val folder = info?.folder
    val subtitle = info?.let {
        listOfNotNull(
            stringResource(R.string.home_on_off, it.on, it.off),
            it.sharedWith.takeIf { s -> s.isNotEmpty() }?.let { s -> stringResource(R.string.home_shared_with, s.joinToString { i -> i.name }) },
        ).joinToString(" · ")
    }
    DashTile(
        stringResource(R.string.home_folder), PixelGlyph.Chest,
        folder?.name ?: stringResource(if (info == null) R.string.home_checking else R.string.home_no_folder),
        onClick, modifier, subtitle = subtitle, tone = if (folder != null) Tone.Accent else Tone.Neutral,
    ) {
        UpdatesBadge(model)
        if (info != null && info.sharedWith.isNotEmpty()) Badge(stringResource(R.string.home_shared_count, info.sharedWith.size + 1))
    }
}

@Composable
private fun UpdatesBadge(model: HomeModel) {
    val result = model.updates ?: return
    when {
        result.updates.isNotEmpty() -> Badge(pluralStringResource(R.plurals.home_updates, result.updates.size, result.updates.size), tone = Tone.Accent)
        result.offline -> Badge(stringResource(R.string.home_offline))
        else -> Badge(stringResource(R.string.home_up_to_date), tone = Tone.Success)
    }
}

@Composable
private fun FolderPanel(graph: AppGraph, model: HomeModel) {
    val info = model.folder ?: return Note(stringResource(R.string.home_checking))
    val folder = info.folder
    if (model.running) Banner(stringResource(R.string.home_folder_read_only), PixelGlyph.Key, tone = Tone.Warn)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        if (info.choices.isNotEmpty()) {
            SelectButton(
                stringResource(R.string.home_folder),
                folder?.id ?: "",
                { if (it.isNotEmpty() && it != folder?.id) model.link(it) },
                (if (folder == null) listOf("" to stringResource(R.string.home_no_folder)) else emptyList()) + info.choices.map { it.id to it.name },
                Modifier.weight(1f).testTag("home.folder.switch"),
            )
        } else {
            Note(stringResource(R.string.home_no_fitting_folder), Modifier.weight(1f))
        }
        folder?.let { BlockButton(stringResource(R.string.home_open_in_mods), { graph.nav.openFolder(it.id) }, icon = PixelGlyph.Book, fullWidth = false) }
    }
    if (info.sharedWith.isNotEmpty()) {
        Banner(stringResource(R.string.home_shared_banner, info.sharedWith.joinToString { it.name }), PixelGlyph.Chest, tone = Tone.Accent) {
            BlockButton(stringResource(R.string.home_copy_folder), model::copyFolder, size = ButtonSize.Small, enabled = !model.running)
        }
    }
    val result = model.updates
    when {
        folder == null -> Unit
        result == null -> Note(stringResource(R.string.home_updates_off))
        result.updates.isEmpty() -> Note(
            if (result.offline) stringResource(R.string.home_offline_checked, Fmt.ago(result.checkedAt) ?: stringResource(R.string.home_never))
            else stringResource(R.string.home_no_updates),
        )
        else -> ListSection(label = pluralStringResource(R.plurals.home_updates, result.updates.size, result.updates.size)) {
            result.updates.forEach { update ->
                BlockRow(
                    update.name,
                    subtitle = "${update.currentVersion ?: "?"} → ${update.target.version}",
                    leading = { PixelIcon(PixelGlyph.Ore, size = 18.dp) },
                    trailing = {
                        BlockButton(
                            stringResource(R.string.home_update), { model.update(listOf(update)) }, size = ButtonSize.Small,
                            loading = update.file in model.updating, enabled = model.updating.isEmpty() && !model.running,
                        )
                    },
                )
            }
        }
    }
    if (info.quick.isNotEmpty()) {
        ListSection(label = stringResource(R.string.home_quick_toggles)) {
            info.quick.forEach { mod ->
                ToggleRow(mod.name, mod.enabled, { model.setEnabled(mod, it) }, subtitle = mod.version.takeIf { it.isNotBlank() }, enabled = !model.running)
            }
        }
    }
    result?.updates?.takeIf { it.size > 1 }?.let { all ->
        BlockButton(
            stringResource(R.string.home_update_all, all.size), { model.update(all) }, Modifier.testTag("home.updateAll"),
            role = ButtonRole.Primary, loading = HomeModel.FIX_ALL in model.updating, enabled = model.updating.isEmpty() && !model.running,
        )
    }
}

// 3 · Performance

@Composable
private fun PerformanceTile(graph: AppGraph, model: HomeModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val perf = model.performance
    val current = graph.library.instances.firstOrNull { it.id == model.instance.id } ?: model.instance
    val value = perf?.let { stringResource(R.string.home_perf_value, Fmt.memory(graph.launcher.heapMB(current)), it.render) } ?: stringResource(R.string.home_checking)
    val subtitle = perf?.let {
        listOfNotNull(
            it.preset?.let { p -> stringResource(p.title) } ?: stringResource(R.string.home_perf_custom),
            stringResource(R.string.home_fps, it.fps),
            deviceText(model.device),
        ).joinToString(" · ")
    }
    DashTile(stringResource(R.string.home_performance), PixelGlyph.Bolt, value, onClick, modifier, subtitle = subtitle) {
        Badge(perf?.preset?.let { stringResource(it.title) } ?: stringResource(R.string.home_perf_custom))
    }
}

@Composable
private fun deviceText(device: HomeModel.Device?): String? {
    device ?: return null
    return listOfNotNull(
        device.celsius?.let { stringResource(R.string.home_celsius, it.roundToInt()) },
        device.batteryPercent?.let { stringResource(if (device.charging) R.string.home_battery_charging else R.string.home_battery, it) },
    ).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun PerformancePanel(graph: AppGraph, model: HomeModel) {
    val perf = model.performance ?: return Note(stringResource(R.string.home_checking))
    val chunks = stringResource(R.string.home_chunks)
    val current = graph.library.instances.firstOrNull { it.id == model.instance.id } ?: model.instance
    val enabled = !model.running
    if (!enabled) Banner(stringResource(R.string.home_perf_running), PixelGlyph.Key, tone = Tone.Warn)
    Segmented(perf.preset, { it?.let(model::setPreset) }, HomeModel.Preset.entries.map { it to stringResource(it.title) }, Modifier.fillMaxWidth().testTag("home.preset"))
    val auto = Fmt.memory(graph.launcher.heapMB(current.copy(autoMemory = true)))
    val sizes = listOf(2048, 3072, 4096, 6144, 8192, 12288).filter { it <= graph.physicalMB * 3 / 4 }
    SectionLabel(stringResource(R.string.play_memory))
    SelectButton(
        stringResource(R.string.play_memory),
        if (current.autoMemory || current.memoryMB == null) 0 else current.memoryMB!!,
        { mb -> model.setMemory(mb == 0, if (mb == 0) current.memoryMB ?: 4096 else mb) },
        listOf(0 to stringResource(R.string.home_memory_auto, auto)) + sizes.map { it to Fmt.memory(it) },
    )
    BlockSlider(
        stringResource(R.string.home_render_distance), perf.render.toFloat(), { model.setOptions(perf.copy(render = it.roundToInt())) },
        2f..32f, { chunks.format(it.roundToInt()) },
    )
    BlockSlider(
        stringResource(R.string.home_simulation_distance), perf.simulation.toFloat(), { model.setOptions(perf.copy(simulation = it.roundToInt())) },
        5f..32f, { chunks.format(it.roundToInt()) },
    )
    SectionLabel(stringResource(R.string.home_fps_limit))
    Segmented(perf.fps, { model.setOptions(perf.copy(fps = it)) }, listOf(30, 60, 90, 120).map { it to "$it" }, Modifier.fillMaxWidth())
    val device = model.device
    BlockRow(
        deviceText(device) ?: stringResource(R.string.home_device_unknown),
        subtitle = device?.thermal?.let { stringResource(thermalText(it)) },
        leading = { PixelIcon(PixelGlyph.Bolt, size = 18.dp) },
    )
    Note(stringResource(R.string.home_perf_note, model.instance.name))
}

private fun thermalText(status: Int): Int = when {
    status <= PowerManager.THERMAL_STATUS_NONE -> R.string.home_thermal_good
    status <= PowerManager.THERMAL_STATUS_MODERATE -> R.string.home_thermal_warm
    else -> R.string.home_thermal_hot
}

// 4 · Server

@Composable
private fun ServerTile(model: HomeModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val server = model.server
    val ping = server?.let { model.servers.pings[it.id] }
    val online = (ping as? ServersModel.Ping.Online)?.status
    val value = when {
        server == null -> stringResource(R.string.home_no_servers)
        online != null -> online.online?.let { n -> online.max?.let { stringResource(R.string.home_online_of, n, it) } ?: stringResource(R.string.servers_players, "$n") }
            ?: stringResource(R.string.home_online)
        ping == ServersModel.Ping.Offline -> stringResource(R.string.home_offline)
        else -> stringResource(R.string.home_checking)
    }
    DashTile(
        server?.record?.name ?: stringResource(R.string.servers_title), PixelGlyph.Grass, value, onClick, modifier,
        subtitle = listOfNotNull(online?.let { "${it.latencyMillis} ms" }, server?.record?.ip).joinToString(" · ").ifEmpty { null },
        tone = if (online != null) Tone.Success else Tone.Neutral,
    ) {
        if (model.servers.servers.size > 1) Badge(pluralStringResource(R.plurals.home_servers, model.servers.servers.size, model.servers.servers.size))
    }
}

@Composable
internal fun pingText(ping: ServersModel.Ping?): String? = when (ping) {
    is ServersModel.Ping.Online -> {
        val players = ping.status.online?.let { online -> ping.status.max?.let { "$online/$it" } ?: "$online" }
        listOfNotNull(players?.let { stringResource(R.string.servers_players, it) }, "${ping.status.latencyMillis} ms").joinToString(" · ")
    }
    ServersModel.Ping.Offline -> stringResource(R.string.servers_offline)
    else -> null
}

// 5 · Last session

@Composable
private fun SessionTile(model: HomeModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val session = model.session
    DashTile(
        stringResource(R.string.home_session), PixelGlyph.Book,
        session?.let { duration(it) } ?: stringResource(R.string.home_never_played),
        onClick, modifier,
        subtitle = session?.let { listOfNotNull(Fmt.ago(it.endedAt), endedText(it)).joinToString(" · ") },
        tone = if (session?.crashed == true) Tone.Warn else Tone.Neutral,
    ) {
        session?.let {
            if (it.crashed) Badge(stringResource(R.string.home_crashed), tone = Tone.Danger) else Badge(stringResource(R.string.home_no_errors), tone = Tone.Success)
        }
    }
}

@Composable
private fun duration(session: Launcher.Session): String {
    if (session.startedAt <= 0 || session.endedAt <= session.startedAt) return "—"
    val seconds = ((session.endedAt - session.startedAt) / 1000).toInt()
    val minutes = seconds / 60
    return when {
        seconds < 60 -> stringResource(R.string.home_seconds, seconds)
        minutes < 60 -> stringResource(R.string.home_minutes, minutes)
        else -> stringResource(R.string.home_hours, minutes / 60, minutes % 60)
    }
}

@Composable
private fun endedText(session: Launcher.Session): String =
    if (session.crashed) stringResource(R.string.home_ended_crash, GameExitReport(1, session.exit).title) else stringResource(R.string.home_ended_quit)

@Composable
private fun SessionPanel(model: HomeModel) {
    val session = model.session ?: return EmptyState(stringResource(R.string.home_never_played), stringResource(R.string.home_session_empty), PixelGlyph.Book)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Stat(duration(session), stringResource(R.string.home_played), Modifier.weight(1f))
        Stat(stringResource(if (session.crashed) R.string.home_crashed else R.string.home_quit), stringResource(R.string.home_ended), Modifier.weight(1f), tone = if (session.crashed) Tone.Danger else Tone.Neutral)
        Stat(session.quickPlay?.label ?: "—", stringResource(R.string.home_played_in), Modifier.weight(1f))
    }
    if (session.crashed) {
        val report = GameExitReport(1, session.exit, session.clue, mod = session.mod)
        Banner(listOfNotNull(report.title, session.mod ?: session.clue).joinToString(": "), PixelGlyph.Cross, tone = Tone.Danger)
        Note(report.explanation)
        session.mod?.let { mod ->
            BlockButton(stringResource(R.string.home_turn_off_and_play, mod), { model.turnOffAndPlay(mod) }, Modifier.testTag("home.turnOffAndPlay"), role = ButtonRole.Primary, icon = PixelGlyph.Play, enabled = !model.running)
        }
    }
    BlockRow(
        "latest.log",
        subtitle = stringResource(if (session.crashed) R.string.home_log_crash else R.string.home_log_clean),
        leading = { PixelIcon(PixelGlyph.Paper, size = 18.dp) },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BlockButton(stringResource(R.string.home_view), model::loadLog, Modifier.testTag("home.viewLog"), size = ButtonSize.Small)
                BlockButton(stringResource(R.string.home_share), model::shareLog, size = ButtonSize.Small)
            }
        },
    )
    model.logLines?.let { lines -> LogSheet(lines, onDismiss = model::closeLog) }
}

@Composable
private fun LogSheet(lines: List<String>, onDismiss: () -> Unit) {
    BlockSheet(onDismiss) {
        SheetHeader("latest.log", onDismiss)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (lines.isEmpty()) Note(stringResource(R.string.home_log_empty))
            LogView(
                lines.map { LogLine("", "Game", it, if (GameExitReport.isError(it)) LogLevel.Error else LogLevel.Info) },
                Modifier.fillMaxWidth().height(320.dp),
                follow = true,
            )
        }
    }
}

// 6 · Screenshots

@Composable
private fun ScreenshotsTile(model: HomeModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shots = model.screenshots
    DashTile(
        stringResource(R.string.home_screenshots), PixelGlyph.Paper, "${shots.size}", onClick, modifier,
        subtitle = shots.firstOrNull()?.let { stringResource(R.string.home_newest, Fmt.ago(it.lastModified()).orEmpty()) } ?: stringResource(R.string.home_no_screenshots),
    ) {
        shots.take(3).forEachIndexed { i, file -> Thumb(file, i) }
    }
}

@Composable
private fun Thumb(file: java.io.File, seed: Int) {
    Box(Modifier.size(44.dp, 25.dp).outline()) {
        RemoteImage(file.uri, Modifier.fillMaxSize(), ContentScale.Crop, placeholder = { PixelScene(20 + seed, Modifier.fillMaxSize(), width = 36, height = 21) })
    }
}

@Composable
private fun ScreenshotsPanel(model: HomeModel, onView: (Int) -> Unit) {
    val shots = model.screenshots
    if (shots.isEmpty()) {
        EmptyState(stringResource(R.string.home_no_screenshots), stringResource(R.string.home_screenshots_empty), PixelGlyph.Paper)
        return
    }
    Box(Modifier.fillMaxWidth().block(BlockKind.Well).padding(8.dp)) {
        GalleryGrid(shots.map { it.galleryItem() }, onView)
    }
}
