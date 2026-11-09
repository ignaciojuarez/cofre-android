package com.ignacio.cofre.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.BuildConfig
import com.ignacio.cofre.R
import com.ignacio.cofre.SettingsPane
import com.ignacio.cofre.design.AdaptiveListDetail
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockKind
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.BlockSlider
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.Chevron
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ColumnHeader
import com.ignacio.cofre.design.ControllerAction
import com.ignacio.cofre.design.ControllerHintBar
import com.ignacio.cofre.design.DesignGallery
import com.ignacio.cofre.design.Field
import com.ignacio.cofre.design.FieldKind
import com.ignacio.cofre.design.ListSection
import com.ignacio.cofre.design.LogView
import com.ignacio.cofre.design.Meta
import com.ignacio.cofre.design.Pad
import com.ignacio.cofre.design.PaneScroll
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.PixelIcon
import com.ignacio.cofre.design.Segmented
import com.ignacio.cofre.design.ToggleRow
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.ValueRow
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.block
import com.ignacio.cofre.design.controllerActions
import com.ignacio.cofre.kit.logs.LogFile
import com.ignacio.cofre.kit.options.JavaHeap
import com.ignacio.cofre.ui.common.Fmt
import com.ignacio.cofre.ui.common.Note
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Settings: a pane list beside the open pane (pushed on phones). */
@Composable
fun SettingsSection(
    graph: AppGraph,
    modifier: Modifier = Modifier,
) {
    val nav = graph.nav
    AdaptiveListDetail(
        selection = nav.settingsPane,
        onSelectionChange = { nav.settingsPane = it },
        list = { SettingsList(nav.settingsPane, onOpen = { nav.settingsPane = it }) },
        detail = { pane -> SettingsPaneContent(graph, pane) },
        modifier = modifier,
    )
}

@Composable
private fun SettingsList(selected: SettingsPane?, onOpen: (SettingsPane) -> Unit) {
    val groups = listOf(
        R.string.settings_group_game to listOf(SettingsPane.Game, SettingsPane.Controller),
        R.string.settings_group_account to listOf(SettingsPane.Accounts, SettingsPane.CurseForge),
        R.string.settings_group_app to SettingsPane.entries.filter {
            it in listOf(SettingsPane.Storage, SettingsPane.Logs, SettingsPane.About, SettingsPane.Licenses) || (it.debugOnly && BuildConfig.DEBUG)
        },
    )
    Column {
        ColumnHeader(stringResource(R.string.section_settings))
        PaneScroll {
            groups.forEach { (label, panes) ->
                ListSection(label = stringResource(label)) {
                    panes.forEach { pane ->
                        BlockRow(
                            stringResource(pane.title),
                            Modifier.testTag("settings.${pane.name}"),
                            selected = pane == selected,
                            onClick = { onOpen(pane) },
                            leading = { PixelIcon(pane.glyph, size = 18.dp) },
                            trailing = { Chevron() },
                        )
                    }
                }
            }
        }
    }
}

private val SettingsPane.glyph: PixelGlyph
    get() = when (this) {
        SettingsPane.Game -> PixelGlyph.Grass
        SettingsPane.Controller -> PixelGlyph.Cmd
        SettingsPane.Accounts -> PixelGlyph.Head
        SettingsPane.CurseForge -> PixelGlyph.Key
        SettingsPane.Storage -> PixelGlyph.Chest
        SettingsPane.Logs -> PixelGlyph.Paper
        SettingsPane.About -> PixelGlyph.Book
        SettingsPane.Licenses -> PixelGlyph.Shield
        SettingsPane.Gallery -> PixelGlyph.Ore
    }

@Composable
private fun SettingsPaneContent(graph: AppGraph, pane: SettingsPane) {
    if (pane == SettingsPane.Gallery) {
        DesignGallery()
        return
    }
    Column(Modifier.fillMaxSize()) {
        ColumnHeader(stringResource(pane.title))
        when (pane) {
            SettingsPane.Logs -> LogsPane(graph)
            else -> PaneScroll(readable = true) {
                when (pane) {
                    SettingsPane.Game -> GamePane(graph)
                    SettingsPane.Controller -> ControllerPane(graph)
                    SettingsPane.Accounts -> AccountsPanel(graph.accounts)
                    SettingsPane.CurseForge -> CurseForgePane(graph)
                    SettingsPane.Storage -> StoragePane(graph)
                    SettingsPane.About -> AboutPane()
                    SettingsPane.Licenses -> LicensesPane()
                }
            }
        }
    }
}

@Composable
private fun GamePane(graph: AppGraph) {
    val settings = graph.settings.value
    val auto = JavaHeap.autoMB(graph.physicalMB)
    ListSection(label = stringResource(R.string.game_memory)) {
        ToggleRow(
            stringResource(R.string.game_auto_memory),
            settings.autoMemory,
            { on -> graph.settings.update { it.copy(autoMemory = on) } },
            subtitle = stringResource(R.string.game_auto_memory_note, Fmt.memory(auto), Fmt.memory(graph.physicalMB)),
        )
        if (!settings.autoMemory) {
            BlockSlider(
                stringResource(R.string.game_memory_limit),
                settings.memoryMB.toFloat(),
                { mb -> graph.settings.update { it.copy(memoryMB = mb.toInt()) } },
                valueRange = 1024f..maxHeap(graph.physicalMB).toFloat(),
                valueText = { Fmt.memory(it.toInt()) },
                step = 512f,
                note = stringResource(R.string.game_memory_note),
                defaultValue = auto.toFloat(),
            )
        }
    }
    ListSection(label = stringResource(R.string.game_display)) {
        ToggleRow(
            stringResource(R.string.game_immersive),
            settings.immersive,
            { on -> graph.settings.update { it.copy(immersive = on) } },
            subtitle = stringResource(R.string.game_immersive_note),
        )
    }
    ListSection(label = stringResource(R.string.game_downloads)) {
        ToggleRow(
            stringResource(R.string.game_metered),
            settings.allowMetered,
            { on -> graph.settings.update { it.copy(allowMetered = on) } },
            subtitle = stringResource(R.string.game_metered_note),
        )
    }
    Note(stringResource(R.string.game_in_game_note))
}

/** The biggest heap worth offering: physical RAM minus room for Android, in 512 MiB steps. */
fun maxHeap(physicalMB: Int): Int = ((physicalMB * 3 / 4) / 512 * 512).coerceAtLeast(2048)

@Composable
private fun ControllerPane(graph: AppGraph) {
    val settings = graph.settings.value
    ListSection {
        ToggleRow(
            stringResource(R.string.controller_swap),
            settings.swapConfirmBack,
            { on -> graph.settings.update { it.copy(swapConfirmBack = on) } },
            Modifier.testTag("controller.swap"),
            subtitle = stringResource(R.string.controller_swap_note),
        )
    }
    ListSection(label = stringResource(R.string.controller_buttons)) {
        listOf(
            R.string.controller_a, R.string.controller_b, R.string.controller_shoulders, R.string.controller_triggers,
            R.string.controller_y, R.string.controller_start,
        ).forEach { BlockRow(stringResource(it)) }
    }
    ControllerHintBar(Modifier.block(), alwaysShow = true)
    Note(stringResource(R.string.controller_in_game))
}

@Composable
private fun CurseForgePane(graph: AppGraph) {
    val current by graph.curseForgeKey.collectAsStateWithLifecycle()
    val key = rememberTextFieldState(current.orEmpty())
    ListSection {
        Field(key, stringResource(R.string.curseforge_key), kind = FieldKind.Secure, placeholder = stringResource(R.string.curseforge_placeholder))
        Note(stringResource(R.string.curseforge_note))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BlockButton(stringResource(R.string.save), { graph.setCurseForgeKey(key.text.toString()) }, Modifier.weight(1f), role = ButtonRole.Primary, enabled = key.text.isNotBlank() && key.text.toString() != current)
        BlockButton(stringResource(R.string.clear), { graph.setCurseForgeKey(null); key.edit { replace(0, length, "") } }, Modifier.weight(1f), enabled = current != null)
    }
    Meta(stringResource(if (current != null) R.string.curseforge_saved else R.string.curseforge_none), tone = if (current != null) Tone.Success else null)
}

@Composable
private fun StoragePane(graph: AppGraph) {
    val model = remember { StorageModel(graph) }
    val context = LocalContext.current
    LaunchedEffect(model) { model.refresh() }
    ListSection(label = stringResource(R.string.storage_used)) {
        model.areas.forEach { area -> BlockRow(areaName(area.name), trailing = { Meta(Fmt.bytes(context, area.bytes)) }) }
        BlockRow(stringResource(R.string.storage_free), trailing = { Meta(Fmt.bytes(context, model.free)) })
    }
    ListSection(label = stringResource(R.string.storage_cleanup)) {
        BlockRow(stringResource(R.string.storage_reclaimable), trailing = { Meta(Fmt.bytes(context, model.reclaimable)) })
        Note(stringResource(R.string.storage_cleanup_note))
    }
    model.freed?.let { Banner(stringResource(R.string.storage_freed, Fmt.bytes(context, it)), PixelGlyph.Check, tone = Tone.Success) }
    BlockButton(stringResource(R.string.storage_clear), model::clear, enabled = model.reclaimable > 0, loading = model.clearing)
}

@Composable
private fun areaName(folder: String): String = when (folder) {
    "instances" -> stringResource(R.string.storage_instances)
    "minecraft" -> stringResource(R.string.storage_game)
    "runtimes" -> stringResource(R.string.storage_java)
    "downloads" -> stringResource(R.string.storage_downloads)
    "logs" -> stringResource(R.string.settings_logs)
    "accounts" -> stringResource(R.string.settings_accounts)
    else -> folder.replaceFirstChar { it.uppercase() }
}

@Composable
private fun LogsPane(graph: AppGraph) {
    val model = remember { LogsModel(graph) }
    val query = rememberTextFieldState()
    val search = remember { FocusRequester() }
    LaunchedEffect(model) {
        snapshotFlow { query.text.toString() to model.source }.collectLatest { (text, _) ->
            while (true) {
                model.load(text)
                delay(1000)
            }
        }
    }
    Column(
        Modifier.fillMaxSize().controllerActions(ControllerAction(Pad.Y, stringResource(R.string.search)) { search.requestFocus() }),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmented(
                model.source,
                { model.source = it },
                listOf(LogFile.Launcher to stringResource(R.string.logs_launcher), LogFile.Game to stringResource(R.string.logs_game), LogFile.Crash to stringResource(R.string.logs_crash)),
                Modifier.padding(horizontal = 20.dp),
            )
            Field(query, stringResource(R.string.search), Modifier.padding(horizontal = 20.dp).focusRequester(search), kind = FieldKind.Search)
        }
        if (model.lines.isEmpty()) {
            Note(stringResource(R.string.logs_empty), Modifier.padding(horizontal = 20.dp))
        }
        LogView(model.lines, Modifier.padding(horizontal = 20.dp).fillMaxWidth().weight(1f).heightIn(min = 120.dp))
        BlockButton(stringResource(R.string.logs_share), model::share, Modifier.padding(horizontal = 20.dp), icon = PixelGlyph.Paper)
    }
}

@Composable
private fun AboutPane() {
    val uri = LocalUriHandler.current
    ListSection {
        ValueRow(stringResource(R.string.about_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", {}, chevron = false)
        ValueRow(stringResource(R.string.about_target), "Minecraft 26.2 · NeoForge · Vulkan", {}, chevron = false)
        ValueRow(stringResource(R.string.about_source), "GitHub", { uri.openUri("https://github.com/ignaciojuarez/cofre-android") }, accent = true)
        ValueRow(stringResource(R.string.about_author), "Ignacio Juarez", { uri.openUri("https://github.com/ignaciojuarez") })
    }
    Note(stringResource(R.string.about_note))
}

@Composable
private fun LicensesPane() {
    val resources = LocalResources.current
    val notice by produceState("") { value = runCatching { resources.assets.open("NOTICE.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
    val ofl by produceState("") { value = runCatching { resources.openRawResource(com.ignacio.cofre.design.R.raw.ofl).bufferedReader().use { it.readText() } }.getOrDefault("") }
    var showOfl by rememberSaveable { mutableStateOf(false) }
    CofreText(notice, CofreTheme.type.code, Modifier.fillMaxWidth().block(BlockKind.Well).padding(horizontal = 12.dp))
    BlockButton(stringResource(if (showOfl) R.string.licenses_hide_ofl else R.string.licenses_show_ofl), { showOfl = !showOfl }, size = ButtonSize.Small, fullWidth = false)
    if (showOfl) CofreText(ofl, CofreTheme.type.code, Modifier.fillMaxWidth().block(BlockKind.Well).padding(horizontal = 12.dp))
}
