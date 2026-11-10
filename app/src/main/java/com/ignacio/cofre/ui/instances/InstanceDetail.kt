package com.ignacio.cofre.ui.instances

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.design.Badge
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.ControllerAction
import com.ignacio.cofre.design.Pad
import com.ignacio.cofre.design.SectionLabel
import com.ignacio.cofre.design.controllerActions
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockSlider
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ColumnHeader
import com.ignacio.cofre.design.Field
import com.ignacio.cofre.design.FieldKind
import com.ignacio.cofre.design.ListSection
import com.ignacio.cofre.design.PaneScroll
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.Segmented
import com.ignacio.cofre.design.SelectButton
import com.ignacio.cofre.design.Stat
import com.ignacio.cofre.design.ToggleRow
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.options.JavaHeap
import com.ignacio.cofre.ui.common.Fmt
import com.ignacio.cofre.ui.common.Note
import com.ignacio.cofre.ui.common.SceneHeader
import com.ignacio.cofre.ui.common.seed
import com.ignacio.cofre.ui.settings.maxHeap
import com.ignacio.cofre.ui.worlds.WorldsList

private enum class DetailTab(val title: Int) { Overview(R.string.instance_overview), Worlds(R.string.instance_worlds), Settings(R.string.instance_settings) }

/** One instance: overview and actions, its worlds, and its settings. */
@Composable
fun InstanceDetail(
    graph: AppGraph,
    instance: Instance,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable(instance.id) { mutableStateOf(DetailTab.Overview) }
    Column(modifier.fillMaxSize()) {
        ColumnHeader(instance.name) {
            BlockButton(stringResource(R.string.play), { graph.launcher.play(instance) }, Modifier.testTag("instance.play"), role = ButtonRole.Primary, size = ButtonSize.Small, icon = PixelGlyph.Play)
        }
        PaneScroll {
            SceneHeader(instance.seed, 130.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge("NeoForge ${instance.loaderVersion ?: instance.gameVersion}", tone = Tone.NeoForge)
                    Badge("Vulkan", tone = Tone.Success)
                    instance.group?.let { Badge(it) }
                }
            }
            Segmented(tab, { tab = it }, DetailTab.entries.map { it to stringResource(it.title) })
            when (tab) {
                DetailTab.Overview -> Overview(graph, instance)
                DetailTab.Worlds -> WorldsList(graph, instance)
                DetailTab.Settings -> InstanceSettings(graph, instance)
            }
        }
    }
}

@Composable
private fun Overview(graph: AppGraph, instance: Instance) {
    val library = graph.library
    val context = LocalContext.current
    val stats by produceState<Library.Stats?>(null, instance) { value = library.stats(instance) }
    val folders = rememberFolders(graph)
    var picking by rememberSaveable(instance.id) { mutableStateOf(false) }
    var deleting by rememberSaveable(instance.id) { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { library.export(instance.id, it) }
    }
    val folderName = folders?.firstOrNull { it.folder.id == instance.modFolder }?.folder?.name
    Column(
        Modifier.controllerActions(ControllerAction(Pad.X, stringResource(R.string.link_section)) { picking = true }),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel(stringResource(R.string.link_section))
        FolderBlock(graph, instance, folders, onChange = { picking = true })
        library.error?.let { Banner(it, PixelGlyph.Cross, tone = Tone.Danger) { BlockButton(stringResource(R.string.dismiss), library::clearError, size = ButtonSize.Small) } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat(stats?.mods?.toString() ?: "—", stringResource(R.string.instance_mods), Modifier.weight(1f))
            Stat(stats?.bytes?.let { Fmt.bytes(context, it) } ?: "—", stringResource(R.string.instance_size), Modifier.weight(1f))
            Stat(Fmt.ago(instance.lastPlayedAt) ?: stringResource(R.string.instance_never), stringResource(R.string.instance_played), Modifier.weight(1f))
        }
        if (instance.notes.isNotBlank()) CofreText(instance.notes, CofreTheme.type.body, color = CofreTheme.colors.inkSoft)
        ListSection(label = stringResource(R.string.instance_section)) {
            ToggleRow(stringResource(R.string.instance_favorite), instance.favorite, { library.favorite(instance.id, it) })
        }
        BlockButton(stringResource(R.string.instance_duplicate), { library.duplicate(instance.id) { graph.nav.instance = it.id } })
        folderName?.let { Note(stringResource(R.string.duplicate_note, it)) }
        BlockButton(stringResource(R.string.instance_export), { exporter.launch("${instance.name}.zip") }, icon = PixelGlyph.Chest)
        BlockButton(stringResource(R.string.instance_delete), { deleting = true }, Modifier.testTag("instance.delete"), role = ButtonRole.GhostDanger)
        Note(stringResource(R.string.instance_delete_note))
    }
    if (picking) FolderPickerSheet(graph, instance) { picking = false }
    if (deleting) DeleteInstanceSheet(graph, instance) { deleting = false }
}

/** Name, group, notes, memory, JVM arguments (with a way back to the last set that worked) and NeoForge version. */
@Composable
private fun InstanceSettings(graph: AppGraph, instance: Instance) {
    val library = graph.library
    LaunchedEffect(Unit) { library.loadNeoForgeVersions() }
    val name = rememberTextFieldState(instance.name)
    val group = rememberTextFieldState(instance.group.orEmpty())
    val notes = rememberTextFieldState(instance.notes)
    val jvm = rememberTextFieldState(instance.jvmArguments)
    val auto = JavaHeap.autoMB(graph.physicalMB)
    var memory by rememberSaveable(instance.id) { mutableStateOf((instance.memoryMB ?: auto).toFloat()) }
    val nameText = name.text.toString().trim()
    ListSection(label = stringResource(R.string.instance_about)) {
        Field(name, stringResource(R.string.instance_name))
        Field(group, stringResource(R.string.instance_group), placeholder = stringResource(R.string.instance_group_hint))
        Field(notes, stringResource(R.string.instance_notes))
    }
    BlockButton(
        stringResource(R.string.save),
        {
            if (nameText != instance.name) library.rename(instance.id, nameText)
            if (group.text.toString() != instance.group.orEmpty()) library.group(instance.id, group.text.toString())
            if (notes.text.toString() != instance.notes) library.notes(instance.id, notes.text.toString())
        },
        enabled = nameText.isNotEmpty() &&
            (nameText != instance.name || group.text.toString() != instance.group.orEmpty() || notes.text.toString() != instance.notes),
    )
    ListSection(label = stringResource(R.string.game_memory)) {
        ToggleRow(
            stringResource(R.string.game_auto_memory),
            instance.autoMemory,
            { on -> library.runtime(instance.id, on, memory.toInt(), instance.jvmArguments) },
            subtitle = stringResource(R.string.instance_auto_memory_note, Fmt.memory(graph.launcher.heapMB(instance.copy(autoMemory = true)))),
        )
        if (!instance.autoMemory) {
            BlockSlider(
                stringResource(R.string.game_memory_limit),
                memory,
                { memory = it },
                valueRange = 1024f..maxHeap(graph.physicalMB).toFloat(),
                valueText = { Fmt.memory(it.toInt()) },
                step = 512f,
                defaultValue = auto.toFloat(),
            )
            BlockButton(stringResource(R.string.instance_apply_memory), { library.runtime(instance.id, false, memory.toInt(), instance.jvmArguments) }, enabled = memory.toInt() != instance.memoryMB)
        }
    }
    ListSection(label = stringResource(R.string.instance_jvm)) {
        Field(jvm, stringResource(R.string.instance_jvm), kind = FieldKind.Mono, placeholder = "-XX:+UseZGC")
        Note(stringResource(R.string.instance_jvm_note))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BlockButton(
            stringResource(R.string.save),
            { library.runtime(instance.id, instance.autoMemory, instance.memoryMB ?: auto, jvm.text.toString()) },
            Modifier.weight(1f),
            enabled = jvm.text.toString().trim() != instance.jvmArguments,
        )
        val canRollback = instance.jvmArguments != instance.lastGoodJVMArguments || instance.memoryMB != instance.lastGoodMemoryMB || instance.autoMemory != instance.lastGoodAutoMemory
        BlockButton(stringResource(R.string.instance_rollback), {
            library.rollbackRuntime(instance.id)
            jvm.edit { replace(0, length, instance.lastGoodJVMArguments) }
        }, Modifier.weight(1f), enabled = canRollback)
    }
    ListSection(label = stringResource(R.string.instance_loader)) {
        SelectButton(
            stringResource(R.string.instance_neoforge),
            instance.loaderVersion ?: Library.handheld.loaderVersion,
            { library.loaderVersion(instance.id, it) },
            library.neoForgeVersions.map { it to it },
        )
        Note(stringResource(R.string.instance_loader_note))
    }
}
