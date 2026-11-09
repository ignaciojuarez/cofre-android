package com.ignacio.cofre.ui.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.R
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockKind
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.block
import com.ignacio.cofre.launch.Launcher

/** "Before you play": mods that need mods that aren't there, or will likely crash on Vulkan. Fix them, or play anyway. */
@Composable
fun PreflightSheet(
    preflight: Launcher.Preflight,
    onGetMods: () -> Unit,
    onTurnOff: () -> Unit,
    onPlayAnyway: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BlockSheet(onDismiss, modifier.testTag("preflight"), hugsContent = true) {
        SheetHeader(stringResource(R.string.preflight_title), onDismiss)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (preflight.missing.isNotEmpty()) {
                CofreText(stringResource(R.string.preflight_body), CofreTheme.type.body)
                Lines(preflight.lines)
            }
            if (preflight.issues.isNotEmpty()) {
                CofreText(stringResource(R.string.preflight_issues_body), CofreTheme.type.body)
                Lines(preflight.issues.map { stringResource(R.string.preflight_issue, it.name, it.reason) })
            }
            preflight.error?.let { Banner(it, PixelGlyph.Cross, tone = Tone.Danger) }
            if (preflight.missing.isNotEmpty()) {
                BlockButton(
                    stringResource(R.string.stopped_get_mods), onGetMods, Modifier.testTag("preflight.getMods"),
                    role = ButtonRole.Primary, size = ButtonSize.Large, loading = preflight.gettingMods, enabled = !preflight.busy,
                )
            }
            if (preflight.issues.isNotEmpty()) {
                BlockButton(
                    stringResource(R.string.preflight_turn_off), onTurnOff, Modifier.testTag("preflight.turnOff"),
                    role = if (preflight.missing.isEmpty()) ButtonRole.Primary else ButtonRole.Secondary,
                    size = ButtonSize.Large, loading = preflight.turningOff, enabled = !preflight.busy,
                )
            }
            BlockButton(
                stringResource(R.string.preflight_play_anyway), onPlayAnyway, Modifier.testTag("preflight.playAnyway"),
                size = ButtonSize.Large, icon = PixelGlyph.Play, enabled = !preflight.busy,
            )
        }
    }
}

@Composable
private fun Lines(lines: List<String>) {
    Column(Modifier.fillMaxWidth().block(BlockKind.Well).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { CofreText(it, CofreTheme.type.code, color = CofreTheme.colors.inkSoft) }
    }
}
