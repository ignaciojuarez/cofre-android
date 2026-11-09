package com.ignacio.cofre.ui.common

import android.content.Context
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.R
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.PixelIcon
import com.ignacio.cofre.design.PixelScene
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.outline
import com.ignacio.cofre.kit.instances.Instance

/** Byte counts and relative times the way Android's own settings show them. */
object Fmt {
    fun bytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun ago(epochMillis: Long?): String? = epochMillis?.let {
        DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }

    fun memory(mb: Int): String = if (mb % 1024 == 0) "${mb / 1024} GB" else "%.1f GB".format(mb / 1024f)
}

/** Same art for an instance on every launch. */
val Instance.seed: Int get() = Math.floorMod(id.hashCode(), 50)

/** "NeoForge 26.2.0.93", or "NeoForge 26.2" before a loader version is picked. */
val Instance.loaderLabel: String get() = "NeoForge ${loaderVersion ?: gameVersion}"

/** A pixel-art banner with a title over a dark fade, like the iOS hero. */
@Composable
fun SceneHeader(
    seed: Int,
    height: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Box(modifier.fillMaxWidth().height(height).clipToBounds().outline()) {
        PixelScene(seed, Modifier.fillMaxSize(), width = 240, height = 110)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

/** A world's icon.png, or a grass block. */
@Composable
fun WorldIcon(
    png: ByteArray?,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    val bitmap = remember(png) { png?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier.size(size).outline(), filterQuality = FilterQuality.None)
    } else {
        PixelIcon(PixelGlyph.Grass, modifier, size = size)
    }
}

/** A short question with a destructive answer, in a focus-trapping sheet. Cancel has focus first. */
@Composable
fun ConfirmSheet(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BlockSheet(onDismiss, modifier, hugsContent = true) {
        SheetHeader(title, onDismiss)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CofreText(message, CofreTheme.type.body, color = CofreTheme.colors.inkSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BlockButton(stringResource(R.string.cancel), onDismiss, Modifier.weight(1f))
                BlockButton(confirm, { onConfirm(); onDismiss() }, Modifier.weight(1f), role = ButtonRole.Danger)
            }
        }
    }
}

/** Muted caption text under a section. */
@Composable
fun Note(
    text: String,
    modifier: Modifier = Modifier,
) {
    CofreText(text, CofreTheme.type.caption, modifier, color = CofreTheme.colors.muted)
}
