package com.ignacio.cofre.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.R
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.IconButton
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.PixelIcon
import com.ignacio.cofre.design.ProgressBar
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.pageScroll
import com.ignacio.cofre.kit.downloads.DownloadItem
import com.ignacio.cofre.ui.common.Fmt

/** Play's strip: only while something downloads, waits or stopped. A big download on mobile data asks first. */
@Composable
fun DownloadsStrip(
    model: Downloads,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (model.asking.isNotEmpty()) {
        val bytes = model.asking.sumOf { it.size ?: 0 }
        Banner(stringResource(R.string.downloads_metered, Fmt.bytes(context, bytes)), PixelGlyph.Chest, modifier, tone = Tone.Warn) {
            BlockButton(stringResource(R.string.downloads_wait), model::waitForWifi, size = ButtonSize.Small)
            BlockButton(stringResource(R.string.downloads_now), model::allowMetered, role = ButtonRole.Primary, size = ButtonSize.Small)
        }
    }
    val summary = model.summary
    if (summary.visible.isEmpty()) return
    BlockRow(
        summary.title,
        modifier.testTag("downloads.strip"),
        onClick = onOpen,
        leading = { if (summary.active.isEmpty()) PixelIcon(PixelGlyph.Chest, size = 18.dp) else Spinner() },
        subtitleContent = { ProgressBar(summary.fraction.toFloat(), small = true) },
    )
}

/** Every queued file with its progress; pause/resume the queue, retry or cancel a file. */
@Composable
fun DownloadsSheet(
    model: Downloads,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    BlockSheet(onDismiss, modifier) {
        SheetHeader(stringResource(R.string.downloads_title), onDismiss) {
            if (model.summary.visible.isNotEmpty()) {
                if (model.running) {
                    BlockButton(stringResource(R.string.downloads_pause), model::pause, size = ButtonSize.Small)
                } else {
                    BlockButton(stringResource(R.string.downloads_resume), model::start, role = ButtonRole.Primary, size = ButtonSize.Small)
                }
            }
        }
        Column(
            Modifier.pageScroll(scroll).verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            model.error?.let { Banner(it, PixelGlyph.Cross, tone = Tone.Danger) }
            if (model.summary.visible.isEmpty()) {
                EmptyState(stringResource(R.string.downloads_empty_title), stringResource(R.string.downloads_empty_body), PixelGlyph.Chest)
            }
            if (model.running && model.bytesPerSecond > 0) {
                BlockRow(stringResource(R.string.downloads_speed, Fmt.bytes(context, model.bytesPerSecond)))
            }
            model.summary.visible.forEach { item ->
                val size = item.size?.let { Fmt.bytes(context, it) }
                BlockRow(
                    item.title,
                    subtitleContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ProgressBar(item.size?.takeIf { it > 0 }?.let { item.completedBytes.toFloat() / it }, small = true, tone = if (item.state == DownloadItem.State.Failed) Tone.Danger else Tone.Success)
                            com.ignacio.cofre.design.Meta(
                                listOfNotNull(stateLabel(item), size?.let { "${Fmt.bytes(context, item.completedBytes)} / $it" }, item.error).joinToString(" · "),
                                tone = if (item.state == DownloadItem.State.Failed) Tone.Danger else null,
                            )
                        }
                    },
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (item.state in listOf(DownloadItem.State.Failed, DownloadItem.State.Paused, DownloadItem.State.WaitingForWifi)) {
                                BlockButton(stringResource(R.string.downloads_retry), { model.retry(item.id) }, size = ButtonSize.Small)
                            }
                            IconButton(PixelGlyph.Cross, stringResource(R.string.cancel), { model.cancel(item.id) })
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun stateLabel(item: DownloadItem): String = stringResource(
    when (item.state) {
        DownloadItem.State.Queued -> R.string.downloads_queued
        DownloadItem.State.Running -> R.string.downloads_running
        DownloadItem.State.WaitingForWifi -> R.string.downloads_waiting
        DownloadItem.State.Paused -> R.string.downloads_paused
        DownloadItem.State.Failed -> R.string.downloads_failed
        DownloadItem.State.Finished -> R.string.downloads_finished
        DownloadItem.State.Cancelled -> R.string.downloads_cancelled
    },
)
