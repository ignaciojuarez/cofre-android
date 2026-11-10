package com.ignacio.cofre.ui.convert

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.design.Badge
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.BlockSwitch
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ControllerAction
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.Pad
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.PixelIcon
import com.ignacio.cofre.design.ProgressBar
import com.ignacio.cofre.design.Radio
import com.ignacio.cofre.design.ScreenFocus
import com.ignacio.cofre.design.Segmented
import com.ignacio.cofre.design.SelectButton
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.blockClickable
import com.ignacio.cofre.design.block
import com.ignacio.cofre.design.controllerActions
import com.ignacio.cofre.design.focusRing
import com.ignacio.cofre.design.pageScroll
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.modfolders.ConvertGap
import com.ignacio.cofre.kit.modfolders.ConvertPlan
import com.ignacio.cofre.kit.modfolders.ConvertRow
import com.ignacio.cofre.kit.modfolders.ConvertStatus
import com.ignacio.cofre.kit.modfolders.ConvertTarget
import com.ignacio.cofre.ui.common.Fmt
import kotlin.math.roundToInt

private val LOADERS = listOf(Loader.NeoForge, Loader.Fabric, Loader.Quilt, Loader.Forge)
private val PROBLEMS = setOf(ConvertStatus.Unavailable, ConvertStatus.Unknown, ConvertStatus.BetaOnly, ConvertStatus.Swap)

/** Convert for the folder in `nav.convertFolder`; back clears it. */
@Composable
fun ConvertScreen(
    graph: AppGraph,
    folderId: String,
    modifier: Modifier = Modifier,
) {
    val nav = graph.nav
    val leave = { nav.convertFolder = null; nav.convertTarget = null }
    val folder = remember(folderId) { runCatching { graph.modFolders.get(folderId) }.getOrNull() }
    if (folder == null) {
        BackHandler(onBack = leave)
        ScreenFocus(modifier.fillMaxSize()) {
            EmptyState(stringResource(R.string.convert_failed_title), stringResource(R.string.convert_missing_folder), PixelGlyph.Chest) {
                BlockButton(stringResource(R.string.convert_retry), leave)
            }
        }
        return
    }
    val model = remember(folderId) { ConvertModel(graph, folder, nav.convertTarget) }
    DisposableEffect(model) { onDispose { model.close() } }
    ConvertScreen(model, onBack = leave, modifier = modifier)
}

@Composable
fun ConvertScreen(
    model: ConvertModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val folder = model.folder
    val target = model.target
    val phase = model.phase
    val plan = (phase as? ConvertPhase.Ready)?.plan
    BackHandler(enabled = model.applying == null, onBack = onBack)
    val problemsLabel = stringResource(R.string.convert_problems_only)
    val createLabel = stringResource(R.string.convert_create_short)
    ScreenFocus(
        modifier.fillMaxSize().controllerActions(
            ControllerAction(Pad.X, problemsLabel) { model.problemsOnly = !model.problemsOnly },
            ControllerAction(Pad.Start, createLabel) { model.create() },
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        // Short phones: the footer scrolls with the table instead of taking half the screen.
        val pinned = maxHeight >= 520.dp
        Column(Modifier.fillMaxSize()) {
            SheetHeader(stringResource(R.string.convert_title, folder.name), onClose = onBack)
            val list = rememberLazyListState()
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().pageScroll(list),
                state = list,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            ) {
                // Table rows touch; everything above them keeps a gap.
                val gap = Modifier.padding(bottom = 10.dp)
                item(key = "target") { TargetBar(model, gap) }
                if (!model.playable) item(key = "banner") {
                    Banner(stringResource(R.string.convert_not_playable, unsupported(target)), PixelGlyph.Shield, gap, tone = Tone.Warn)
                }
                when (phase) {
                    ConvertPhase.Loading -> item(key = "loading") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                            Spinner(size = 18.dp)
                            CofreText(stringResource(R.string.convert_checking, label(target)), CofreTheme.type.secondary, color = CofreTheme.colors.muted)
                        }
                    }
                    is ConvertPhase.Offline -> item(key = "offline") { Problem(stringResource(R.string.convert_offline_title), phase.message, model::retry) }
                    is ConvertPhase.Failed -> item(key = "failed") { Problem(stringResource(R.string.convert_failed_title), phase.message, model::retry) }
                    is ConvertPhase.Ready -> {
                        item(key = "summary") { Summary(model, phase.plan, gap) }
                        val rows = phase.plan.rows.filter { !model.problemsOnly || it.status in PROBLEMS }
                        item(key = "head") { TableHeader(label(folder.loader, folder.gameVersion), label(target)) }
                        if (rows.isEmpty()) item(key = "none") {
                            CofreText(stringResource(R.string.convert_no_problems), CofreTheme.type.secondary, Modifier.padding(vertical = 12.dp), color = CofreTheme.colors.muted)
                        }
                        items(rows, key = { it.jar ?: "+${it.projectId ?: it.name}" }) { row -> TableRow(model, row, target) }
                        if (!pinned) item(key = "footer") { Footer(model, phase.plan, Modifier.padding(top = 10.dp)) }
                    }
                }
            }
            if (plan != null && pinned) Footer(model, plan, Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp))
        }
        }
    }
    model.askMetered?.let { bytes ->
        val context = LocalContext.current
        BlockSheet(model::dismissMetered, hugsContent = true) {
            SheetHeader(stringResource(R.string.convert_metered_title), model::dismissMetered)
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CofreText(stringResource(R.string.convert_metered, Fmt.bytes(context, bytes)), CofreTheme.type.secondary, color = CofreTheme.colors.inkSoft)
                BlockButton(stringResource(R.string.convert_metered_now), { model.create(approvedMetered = true) }, role = ButtonRole.Primary)
                BlockButton(stringResource(R.string.convert_metered_wait), model::dismissMetered)
            }
        }
    }
}

/** "Fabric", "26.3" or "Fabric 26.3": what Cofre would need to support to play [target]. */
private fun unsupported(target: ConvertTarget): String = when {
    target.loader != Loader.NeoForge && target.gameVersion != com.ignacio.cofre.kit.common.Target.GAME_VERSION -> label(target)
    target.loader != Loader.NeoForge -> target.loader.displayName
    else -> target.gameVersion
}

private fun label(target: ConvertTarget): String = label(target.loader, target.gameVersion)
private fun label(loader: Loader, version: String): String = "${loader.displayName} $version"

private fun tone(loader: Loader): Tone = when (loader) {
    Loader.NeoForge -> Tone.NeoForge
    Loader.Fabric -> Tone.Fabric
    Loader.Quilt -> Tone.Quilt
    Loader.Forge -> Tone.Forge
    else -> Tone.Neutral
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetBar(model: ConvertModel, modifier: Modifier = Modifier) {
    val target = model.target
    val folder = model.folder
    val busy = model.applying != null
    val betas = stringResource(R.string.convert_betas)
    FlowRow(
        modifier.fillMaxWidth().block().padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Caption(stringResource(R.string.convert_from))
        Badge(label(folder.loader, folder.gameVersion), tone = tone(folder.loader))
        CofreText("→", CofreTheme.type.rowTitle, color = CofreTheme.colors.muted)
        Caption(stringResource(R.string.convert_to))
        SelectButton(
            stringResource(R.string.convert_loader), target.loader, { if (!busy) model.retarget(target.copy(loader = it)) },
            LOADERS.map { it to it.displayName }, Modifier.width(170.dp),
        )
        SelectButton(
            stringResource(R.string.convert_version), target.gameVersion, { if (!busy) model.retarget(target.copy(gameVersion = it)) },
            model.versions.map { it to it }, Modifier.width(130.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BlockSwitch(target.includeBeta, { if (!busy) model.retarget(target.copy(includeBeta = it)) }, Modifier.semantics { contentDescription = betas })
            Caption(stringResource(R.string.convert_betas))
        }
    }
}

@Composable
private fun Caption(text: String) = CofreText(text, CofreTheme.type.label, color = CofreTheme.colors.muted)

@Composable
private fun Problem(title: String, message: String, onRetry: () -> Unit) {
    EmptyState(title, message, PixelGlyph.Chest) {
        BlockButton(stringResource(R.string.convert_retry), onRetry, role = ButtonRole.Primary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(model: ConvertModel, plan: ConvertPlan, modifier: Modifier = Modifier) {
    val counts = plan.counts
    val badges = listOf(
        ConvertStatus.Ready to (R.string.convert_ready to Tone.Success),
        ConvertStatus.Swap to (R.string.convert_swaps to Tone.Accent),
        ConvertStatus.Added to (R.string.convert_added to Tone.Accent),
        ConvertStatus.BetaOnly to (R.string.convert_beta_only to Tone.Warn),
        ConvertStatus.Unavailable to (R.string.convert_unavailable to Tone.Danger),
        ConvertStatus.NotNeeded to (R.string.convert_not_needed to Tone.Neutral),
        ConvertStatus.Unknown to (R.string.convert_unknown to Tone.Neutral),
    )
    val percent = (plan.carriesOver * 100).roundToInt()
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        for ((status, spec) in badges) {
            val n = counts[status] ?: 0
            if (n > 0) Badge(stringResource(spec.first, n), tone = spec.second)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CofreText(stringResource(R.string.convert_carries_over, percent), CofreTheme.type.secondary, color = CofreTheme.colors.muted)
            ProgressBar(plan.carriesOver, Modifier.width(120.dp), small = true)
        }
        Segmented(
            model.problemsOnly, { model.problemsOnly = it },
            listOf(false to stringResource(R.string.convert_all), true to stringResource(R.string.convert_problems)),
            Modifier.width(220.dp),
            small = true,
        )
    }
}

/** The two columns' shared layout: now (1), arrow, target (1.25). */
@Composable
private fun Columns(
    now: @Composable RowScope.() -> Unit,
    next: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    targetTint: Color = Color.Transparent,
    arrow: String = "→",
) {
    val outline = CofreTheme.colors.outline
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).drawBehind {
            val line = 2.dp.toPx()
            drawRect(outline, Offset(0f, size.height - line), Size(size.width, line))
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = now)
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { CofreText(arrow, CofreTheme.type.rowTitle, color = CofreTheme.colors.muted) }
        Row(
            Modifier.weight(1.25f).heightIn(min = 44.dp).background(targetTint).padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = next,
        )
    }
}

@Composable
private fun TableHeader(now: String, target: String) {
    Columns(
        modifier = Modifier.block(com.ignacio.cofre.design.BlockKind.Raised),
        now = { Caption(stringResource(R.string.convert_now, now)) },
        arrow = "",
        next = { Caption(stringResource(R.string.convert_target, target)) },
    )
}

@Composable
private fun TableRow(model: ConvertModel, row: ConvertRow, target: ConvertTarget) {
    val c = CofreTheme.colors
    val type = CofreTheme.type
    val (tint, mark, markColor) = when (row.status) {
        ConvertStatus.Ready -> Triple(Color.Transparent, "✓", c.success)
        ConvertStatus.Swap -> Triple(c.accent.copy(alpha = 0.08f), "⇄", c.accent)
        ConvertStatus.Added -> Triple(c.accent.copy(alpha = 0.08f), "+", c.accent)
        ConvertStatus.BetaOnly -> Triple(c.warn.copy(alpha = 0.08f), "!", c.warn)
        ConvertStatus.Unavailable -> Triple(c.danger.copy(alpha = 0.08f), "✕", c.danger)
        ConvertStatus.NotNeeded -> Triple(Color.Transparent, "–", c.muted)
        ConvertStatus.Unknown -> Triple(Color.Transparent, "?", c.muted)
    }
    val hasActions = row.status == ConvertStatus.Swap || row.status == ConvertStatus.BetaOnly
    val busy = model.applying != null
    val title: String
    var note: String? = null
    when (row.status) {
        ConvertStatus.Ready -> title = row.target?.version.orEmpty()
        ConvertStatus.Swap -> { title = listOfNotNull(row.suggestion?.title, row.suggestion?.version).joinToString(" "); note = stringResource(R.string.convert_swap_note) }
        ConvertStatus.Added -> { title = stringResource(R.string.convert_added_name, listOfNotNull(row.name, row.target?.version).joinToString(" ")); note = row.reason }
        ConvertStatus.BetaOnly -> title = stringResource(R.string.convert_beta_version, row.target?.version.orEmpty())
        ConvertStatus.Unavailable -> title = when (row.gap) {
            ConvertGap.NoLoaderBuild -> stringResource(R.string.convert_no_loader_build, target.loader.displayName)
            ConvertGap.NotOutYet -> stringResource(R.string.convert_not_out_yet, if (target.loader == model.folder.loader) target.gameVersion else label(target))
            else -> row.reason.orEmpty()
        }
        ConvertStatus.NotNeeded -> title = row.reason.orEmpty()
        ConvertStatus.Unknown -> { title = stringResource(R.string.convert_cant_check); note = row.reason ?: stringResource(R.string.convert_not_on_modrinth) }
    }
    val muted = row.status == ConvertStatus.NotNeeded || row.status == ConvertStatus.Unknown
    Columns(
        modifier = Modifier
            .background(c.panel)
            .then(if (hasActions) Modifier else Modifier.focusRing().focusable())
            .semantics(mergeDescendants = !hasActions) {}
            .alpha(if (row.enabled) 1f else 0.6f),
        targetTint = tint,
        now = {
            if (row.jar == null) {
                CofreText(stringResource(R.string.convert_not_in_folder), type.secondary, color = c.muted)
            } else {
                PixelIcon(if (row.projectId == null) PixelGlyph.Chest else PixelGlyph.Ore, size = 20.dp)
                CofreText(row.name, type.rowTitle, Modifier.weight(1f, fill = false), maxLines = 1)
                row.version?.let { CofreText(it, type.code, color = c.muted, maxLines = 1) }
            }
        },
        next = {
            CofreText(mark, type.rowTitle, color = markColor)
            Column(Modifier.weight(1f)) {
                CofreText(title, if (row.status == ConvertStatus.Ready) type.code else type.rowTitle, color = if (muted) c.muted else c.ink, maxLines = 2)
                note?.let { CofreText(it, type.caption, color = c.muted, maxLines = 1) }
            }
            val jar = row.jar
            if (jar != null && row.status == ConvertStatus.Swap) {
                val using = jar in model.choices.swaps
                BlockButton(stringResource(R.string.convert_use), { if (!busy) model.toggleSwap(jar, true) }, role = if (using) ButtonRole.Primary else ButtonRole.Secondary, size = ButtonSize.Small)
                BlockButton(stringResource(R.string.convert_skip), { if (!busy) model.toggleSwap(jar, false) }, role = if (!using) ButtonRole.Primary else ButtonRole.Secondary, size = ButtonSize.Small)
            }
            if (jar != null && row.status == ConvertStatus.BetaOnly) {
                val using = jar in model.choices.betas
                BlockButton(
                    stringResource(if (using) R.string.convert_using_beta else R.string.convert_use_beta), { if (!busy) model.toggleBeta(jar) },
                    role = if (using) ButtonRole.Primary else ButtonRole.Secondary, size = ButtonSize.Small,
                )
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Footer(model: ConvertModel, plan: ConvertPlan, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val applying = model.applying
    Column(
        modifier.fillMaxWidth().block().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        model.applyError?.let { CofreText(it, CofreTheme.type.secondary, color = CofreTheme.colors.danger) }
        if (applying != null) {
            val (done, total) = applying
            ProgressBar(
                if (total > 0) done / total.toFloat() else null, Modifier.fillMaxWidth(),
                label = stringResource(R.string.convert_applying, done, total),
            )
            return@Column
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Caption(stringResource(R.string.convert_save_as))
            Choice(stringResource(R.string.convert_new_folder, model.newName), model.saveAs == SaveAs.NewFolder) { model.saveAs = SaveAs.NewFolder }
            Choice(
                stringResource(if (model.canReplace) R.string.convert_replace else R.string.convert_replace_blocked),
                model.saveAs == SaveAs.Replace, enabled = model.canReplace,
            ) { model.saveAs = SaveAs.Replace }
            Caption(stringResource(R.string.convert_missing))
            SelectButton(
                stringResource(R.string.convert_missing), model.choices.keepMissingOff, model::keepMissingOff,
                listOf(false to stringResource(R.string.convert_leave_out), true to stringResource(R.string.convert_keep_off)),
                compact = true,
            )
            BlockButton(
                stringResource(R.string.convert_create, model.modCount(plan), Fmt.bytes(context, plan.downloadBytes(model.choices))),
                { model.create() }, role = ButtonRole.Primary, fullWidth = false,
            )
        }
    }
}

/** A radio choice: the row is the button. */
@Composable
private fun Choice(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .blockClickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.RadioButton; this.selected = selected }
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected)
        CofreText(text, CofreTheme.type.rowTitle, color = if (selected) CofreTheme.colors.ink else CofreTheme.colors.inkSoft, maxLines = 1)
    }
}
