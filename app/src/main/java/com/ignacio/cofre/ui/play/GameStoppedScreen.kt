package com.ignacio.cofre.ui.play

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.ignacio.cofre.design.CofreBlock
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ControllerHintBar
import com.ignacio.cofre.design.LogLevel
import com.ignacio.cofre.design.LogLine
import com.ignacio.cofre.design.LogView
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.PixelIcon
import com.ignacio.cofre.design.PixelScene
import com.ignacio.cofre.design.ScreenFocus
import com.ignacio.cofre.design.SectionLabel
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.ValueRow
import com.ignacio.cofre.design.block
import com.ignacio.cofre.design.pageScroll
import com.ignacio.cofre.kit.logs.ExitReason
import com.ignacio.cofre.kit.logs.GameExitReport
import com.ignacio.cofre.launch.Launcher

/**
 * G01 Game stopped: what happened, one next step, then the actions. On Android the launcher survives the game, so
 * Play again really plays again. A fix ("Turn off Distant Horizons") comes first while it applies.
 */
@Composable
fun GameStoppedScreen(
    stopped: Launcher.Stopped,
    onPlayAgain: () -> Unit,
    onTurnOffMod: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var details by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    Column(modifier.fillMaxSize().background(CofreTheme.colors.page).testTag("stopped")) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PixelScene(7, Modifier.fillMaxSize(), width = 240, height = 160)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.75f)))))
            ScreenFocus(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val sideBySide = maxWidth >= 600.dp
                    val scroll = rememberScrollState()
                    Column(
                        Modifier.fillMaxSize().pageScroll(scroll).verticalScroll(scroll).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Column(
                            Modifier.widthIn(max = if (sideBySide) 820.dp else 500.dp).fillMaxWidth().block(lip = CofreBlock.buttonLip).padding(22.dp),
                        ) {
                            if (sideBySide) {
                                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) { Message(stopped) }
                                    Column(Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Actions(stopped, onPlayAgain, onTurnOffMod, onShare, onClose, onDetails = { details = true })
                                    }
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Message(stopped)
                                    Actions(stopped, onPlayAgain, onTurnOffMod, onShare, onClose, onDetails = { details = true })
                                }
                            }
                        }
                    }
                }
            }
        }
        ControllerHintBar()
    }
    if (details) DetailsSheet(stopped.report, onDismiss = { details = false })
}

@Composable
private fun ColumnScope.Message(stopped: Launcher.Stopped) {
    val report = stopped.report
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).block(BlockKind.Tinted(if (report.reason == ExitReason.Quit) Tone.Accent else Tone.Warn)), contentAlignment = Alignment.Center) {
            PixelIcon(PixelGlyph.Head, size = 26.dp)
        }
        Column {
            CofreText(report.title, CofreTheme.type.title)
            CofreText(stopped.instance.name, CofreTheme.type.label, color = CofreTheme.colors.muted)
        }
    }
    CofreText(report.explanation, CofreTheme.type.body)
    report.clue?.let { CofreText(it, CofreTheme.type.code, color = CofreTheme.colors.inkSoft, maxLines = 3) }
    Column(Modifier.fillMaxWidth().block(BlockKind.Well).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(R.string.stopped_next_step))
        val next = stopped.mod?.let { stringResource(R.string.stopped_turn_off_next, it.name) } ?: report.nextStep
        CofreText(next, CofreTheme.type.body, color = CofreTheme.colors.inkSoft)
    }
}

@Composable
private fun Actions(
    stopped: Launcher.Stopped,
    onPlayAgain: () -> Unit,
    onTurnOffMod: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
    onDetails: () -> Unit,
) {
    val mod = stopped.mod
    val fixPending = mod != null && !stopped.modOff
    if (mod != null) {
        if (stopped.modOff) {
            Banner(stringResource(R.string.stopped_mod_off, mod.name), PixelGlyph.Check, tone = Tone.Success)
        } else {
            BlockButton(stringResource(R.string.stopped_turn_off, mod.name), onTurnOffMod, Modifier.testTag("stopped.turnOff"), role = ButtonRole.Primary, size = ButtonSize.Large)
        }
    }
    val again = stopped.quickPlay?.let { stringResource(R.string.stopped_play_again_to, it.label) } ?: stringResource(R.string.stopped_play_again)
    BlockButton(again, onPlayAgain, Modifier.testTag("stopped.playAgain"), role = if (fixPending) ButtonRole.Secondary else ButtonRole.Primary, size = ButtonSize.Large, icon = PixelGlyph.Play)
    BlockButton(stringResource(R.string.close), onClose, role = ButtonRole.Ghost, size = ButtonSize.Large)
    BlockButton(stringResource(R.string.stopped_share), onShare, icon = PixelGlyph.Paper)
    BlockButton(stringResource(R.string.stopped_details), onDetails)
}

@Composable
private fun DetailsSheet(report: GameExitReport, onDismiss: () -> Unit) {
    BlockSheet(onDismiss) {
        SheetHeader(stringResource(R.string.stopped_details), onDismiss)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ValueRow(stringResource(R.string.stopped_exit_code), "${report.code}", {}, chevron = false)
            ValueRow(stringResource(R.string.stopped_reason), report.reason.name, {}, chevron = false)
            if (report.missingVulkanRequirements.isNotEmpty()) {
                CofreText(report.missingVulkanRequirements.joinToString(), CofreTheme.type.code)
            }
            SectionLabel(stringResource(R.string.launch_log))
            LogView(
                report.logTail.map { LogLine("", "Log", it, if (GameExitReport.isError(it)) LogLevel.Error else LogLevel.Info) },
                Modifier.fillMaxWidth().height(220.dp),
            )
        }
    }
}
