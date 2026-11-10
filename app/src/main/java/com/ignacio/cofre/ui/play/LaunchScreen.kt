package com.ignacio.cofre.ui.play

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ControllerHintBar
import com.ignacio.cofre.design.ListSection
import com.ignacio.cofre.design.LogView
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.PixelIcon
import com.ignacio.cofre.design.PixelScene
import com.ignacio.cofre.design.ProgressBar
import com.ignacio.cofre.design.ScreenFocus
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.pageScroll
import com.ignacio.cofre.launch.Launcher
import com.ignacio.cofre.ui.common.Note
import com.ignacio.cofre.ui.common.loaderLabel

/** P02 Launch, full screen: the scene and progress beside the stages and the live log. B cancels. */
@Composable
fun LaunchScreen(
    run: Launcher.Run,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCancel)
    Column(modifier.fillMaxSize().background(CofreTheme.colors.page).testTag("launch")) {
        ScreenFocus(Modifier.weight(1f)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val side = minOf(440.dp, maxWidth * 0.5f)
                // Side by side on every landscape screen, so the error and its actions are never below the fold.
                Row(Modifier.fillMaxSize()) {
                    Hero(run, Modifier.weight(1f).fillMaxHeight())
                    Stages(run, onCancel, onRetry, Modifier.width(side).fillMaxHeight())
                }
            }
        }
        ControllerHintBar()
    }
}

@Composable
private fun Hero(run: Launcher.Run, modifier: Modifier = Modifier) {
    val stages = Launcher.Stage.entries
    val title = when {
        run.error != null -> R.string.launch_failed
        run.done -> R.string.stage_in_game
        else -> R.string.launch_launching
    }
    Box(modifier) {
        PixelScene(7, Modifier.fillMaxSize(), width = 200, height = 140)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.75f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CofreText(
                listOfNotNull("${run.instance.name} · ${run.instance.loaderLabel}", run.quickPlay?.label).joinToString(" → "),
                CofreTheme.type.label,
                color = CofreTheme.colors.inkSoft,
            )
            CofreText(stringResource(title), CofreTheme.type.display)
            ProgressBar((run.stage.ordinal + if (run.done) 1 else 0).toFloat() / stages.size, tone = if (run.error != null) Tone.Danger else Tone.Success)
            CofreText(
                stringResource(R.string.launch_step, run.stage.ordinal + 1, stages.size, stringResource(run.stage.title)),
                CofreTheme.type.label,
                color = CofreTheme.colors.inkSoft,
            )
        }
    }
}

@Composable
private fun Stages(
    run: Launcher.Run,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // A failure first, where it's seen, then the actions: Cancel (or Try again) is where focus starts.
        run.error?.let { Banner(it, PixelGlyph.Cross, Modifier.testTag("launch.error"), tone = Tone.Danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                run.error != null -> {
                    BlockButton(stringResource(R.string.launch_try_again), onRetry, Modifier.weight(1f), role = ButtonRole.Primary)
                    BlockButton(stringResource(R.string.close), onCancel, Modifier.weight(1f).testTag("launch.close"))
                }
                run.done -> BlockButton(stringResource(R.string.close), onCancel, Modifier.weight(1f), role = ButtonRole.Primary)
                else -> BlockButton(stringResource(R.string.cancel), onCancel, Modifier.weight(1f).testTag("launch.cancel"))
            }
        }
        if (run.error == null && !run.done) Note(stringResource(R.string.launch_keep_open))
        Column(Modifier.weight(1f).pageScroll(scroll).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ListSection(label = stringResource(R.string.launch_stages), spacing = 4.dp) {
                Launcher.Stage.entries.forEach { stage ->
                    val state = when {
                        run.done || stage < run.stage -> StageState.Done
                        stage == run.stage && run.error != null -> StageState.Failed
                        stage == run.stage -> StageState.Working
                        else -> StageState.Waiting
                    }
                    BlockRow(
                        stringResource(stage.title),
                        dimmed = state == StageState.Waiting,
                        leading = {
                            when (state) {
                                StageState.Done -> PixelIcon(PixelGlyph.Check, size = 14.dp)
                                StageState.Failed -> PixelIcon(PixelGlyph.Cross, size = 14.dp)
                                StageState.Working -> Spinner()
                                StageState.Waiting -> Box(Modifier.width(14.dp))
                            }
                        },
                    )
                }
            }
            ListSection(label = stringResource(R.string.launch_log)) {
                LogView(run.log, Modifier.fillMaxWidth().height(180.dp))
            }
        }
    }
}

private enum class StageState { Done, Working, Failed, Waiting }
