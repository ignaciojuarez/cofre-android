package com.ignacio.cofre.ui.mods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ignacio.cofre.R
import com.ignacio.cofre.design.Badge
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ListSection
import com.ignacio.cofre.design.Meta
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.ProgressBar
import com.ignacio.cofre.design.SectionLabel
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.ToggleRow
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.kit.compat.CompatibilityIssue
import com.ignacio.cofre.kit.mods.ModCandidate
import com.ignacio.cofre.kit.mods.PlanBlock
import com.ignacio.cofre.kit.mods.VersionChannel

/** M05 wired to a running Get. Closing it mid-install leaves the install running. */
@Composable
internal fun InstallRoute(
    get: InstallModel,
    instanceName: String,
    onOpenUrl: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase by get.phase.collectAsStateWithLifecycle()
    BlockSheet(onClose, modifier, hugsContent = true) {
        InstallContent(
            phase, get.project, get.issue, instanceName,
            onToggle = get::toggle,
            onConfirm = get::confirm,
            onRetry = get::retry,
            onOpenUrl = onOpenUrl,
            onClose = onClose,
        )
    }
}

/** M05 Get sheet: plan → confirm → progress → done or failed, one phase at a time. */
@Composable
internal fun InstallContent(
    phase: InstallPhase,
    project: ProjectSummary,
    issue: CompatibilityIssue?,
    instanceName: String,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SheetHeader(
            if (phase is InstallPhase.Done) project.title else stringResource(R.string.mods_get_title, project.title),
            onClose,
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (phase) {
                InstallPhase.Planning -> BlockRow(stringResource(R.string.mods_planning), leading = { Spinner() })
                is InstallPhase.Confirm -> Confirm(phase, project, issue, instanceName, onToggle, onConfirm)
                is InstallPhase.Blocked -> {
                    Banner(stringResource(R.string.mods_cant_get, project.title), PixelGlyph.Cross, tone = Tone.Danger)
                    CofreText(phase.block.describe(project.title), CofreTheme.type.body, color = CofreTheme.colors.inkSoft)
                    val website = (phase.block as? PlanBlock.DownloadDisabled)?.websiteUrl ?: project.pageUrl.takeIf { phase.block is PlanBlock.DownloadDisabled }
                    if (website != null) {
                        BlockButton(stringResource(R.string.mods_download_website), { onOpenUrl(website) }, role = ButtonRole.Primary)
                    }
                    BlockButton(stringResource(R.string.mods_close), onClose)
                }
                is InstallPhase.Installing -> {
                    ProgressBar(phase.fraction, label = pluralStringResource(R.plurals.mods_downloading, phase.total, phase.total))
                    if (phase.fraction != null) {
                        CofreText(stringResource(R.string.mods_import_files, phase.done, phase.total), CofreTheme.type.label, color = CofreTheme.colors.muted)
                    }
                    Files(phase.files, done = false)
                    BlockButton(stringResource(R.string.mods_hide), onClose)
                }
                is InstallPhase.Done -> {
                    Banner(stringResource(R.string.mods_installed_in, instanceName), PixelGlyph.Check, tone = Tone.Success)
                    Files(phase.files, done = true)
                    BlockButton(stringResource(R.string.mods_done), onClose, role = ButtonRole.Primary)
                }
                is InstallPhase.Failed -> {
                    Banner(stringResource(R.string.mods_couldnt_install, project.title), PixelGlyph.Cross, tone = Tone.Danger)
                    CofreText(phase.reason, CofreTheme.type.body, color = CofreTheme.colors.inkSoft)
                    CofreText(stringResource(R.string.mods_nothing_installed), CofreTheme.type.secondary, color = CofreTheme.colors.muted)
                    BlockButton(stringResource(R.string.mods_try_again), onRetry, role = ButtonRole.Primary)
                    BlockButton(stringResource(R.string.mods_close), onClose)
                }
            }
        }
    }
}

@Composable
private fun Confirm(
    phase: InstallPhase.Confirm,
    project: ProjectSummary,
    issue: CompatibilityIssue?,
    instanceName: String,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    val target = phase.plan.target ?: return
    // The warning leads, so it's read before Get.
    issue?.let {
        Banner(it.badge, PixelGlyph.Ore, tone = it.tone)
        CofreText(it.reason, CofreTheme.type.secondary, color = CofreTheme.colors.muted)
    }
    BlockRow(
        target.title,
        subtitle = listOf(target.version, instanceName).filter { it.isNotBlank() }.joinToString(" · "),
        leading = { ModIcon(project.iconUrl, 32.dp) },
        trailing = { Sized(target) },
    )
    if (target.channel != VersionChannel.Release) {
        CofreText(stringResource(R.string.mods_newest_channel, instanceName, target.channel.name.lowercase()), CofreTheme.type.secondary, color = CofreTheme.colors.muted)
    }
    if (phase.required.isNotEmpty() || phase.plan.turnsOn.isNotEmpty()) {
        ListSection(label = stringResource(R.string.mods_also_installs)) {
            phase.required.forEach { m -> BlockRow(m.title, subtitle = m.version, trailing = { Sized(m) }) }
            phase.plan.turnsOn.forEach { m -> BlockRow(m.name, trailing = { Meta(stringResource(R.string.mods_turns_on), tone = Tone.Warn) }) }
        }
    }
    if (phase.offered.isNotEmpty()) {
        SectionLabel(stringResource(R.string.mods_optional))
        phase.offered.forEach { m ->
            ToggleRow(
                m.title,
                checked = m.projectId in phase.picked,
                onCheckedChange = { onToggle(m.projectId) },
                subtitle = m.size?.let(::size),
                enabled = !phase.replanning,
            )
        }
    }
    if (phase.plan.present.isNotEmpty()) {
        CofreText(stringResource(R.string.mods_already_has, phase.plan.present.joinToString()), CofreTheme.type.secondary, color = CofreTheme.colors.muted)
    }
    val label = when {
        issue != null -> stringResource(R.string.mods_install_anyway)
        phase.files > 1 -> stringResource(R.string.mods_install_count, phase.files)
        else -> stringResource(R.string.mods_install)
    }
    BlockButton(
        "$label · ${size(phase.bytes)}",
        onConfirm,
        role = if (issue == null) ButtonRole.Primary else ButtonRole.Secondary,
        loading = phase.replanning,
    )
}

@Composable
private fun Sized(candidate: ModCandidate) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (candidate.channel != VersionChannel.Release) Badge(candidate.channel.name, tone = Tone.Warn)
        candidate.size?.let { Meta(size(it)) }
    }
}

@Composable
private fun Files(files: List<String>, done: Boolean) {
    ListSection {
        files.take(6).forEach { name ->
            BlockRow(name, leading = { if (done) Badge("✓", tone = Tone.Success) else Spinner() })
        }
        if (files.size > 6) Meta(pluralStringResource(R.plurals.mods_more, files.size - 6, files.size - 6))
    }
}
