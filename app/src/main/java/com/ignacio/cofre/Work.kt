package com.ignacio.cofre

import android.content.Context
import android.net.Uri
import com.ignacio.cofre.kit.worlds.WorldStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Runs [block] in the app scope; a failure's friendly message goes to [onError] instead of crashing. */
fun AppGraph.work(onError: (String) -> Unit, block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(context.message(e))
    }
}

/** File and network work off the main thread. */
suspend fun <T> AppGraph.onIo(block: () -> T): T = withContext(io) { block() }

/** What a user should read for [e]: the kit's own sentence, or a plain fallback. */
fun Context.message(e: Throwable): String = when (e) {
    is WorldStore.Failure -> getString(
        when (e.reason) {
            WorldStore.Reason.GameRunning -> R.string.error_game_running
            WorldStore.Reason.MissingWorld -> R.string.error_missing_world
            WorldStore.Reason.BedrockWorld -> R.string.error_bedrock_world
            WorldStore.Reason.InvalidArchive -> R.string.error_invalid_archive
        },
    )
    else -> e.message?.takeIf { it.isNotBlank() && it.length < 200 } ?: getString(R.string.error_generic)
}

/** Copies a picked document into the cache, for kit APIs that take a [File]. Delete it when done. */
fun AppGraph.copyToCache(uri: Uri, extension: String): File {
    val file = File(context.cacheDir, "picked/${UUID.randomUUID()}.$extension").apply { parentFile?.mkdirs() }
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { context.getString(R.string.error_open_file) }
        file.outputStream().use { input.copyTo(it) }
    }
    return file
}

/** Writes [file] to a document the user created with the system picker, then deletes [file]. */
fun AppGraph.moveToDocument(file: File, uri: Uri) {
    try {
        context.contentResolver.openOutputStream(uri, "wt").use { output ->
            requireNotNull(output) { context.getString(R.string.error_open_file) }
            file.inputStream().use { it.copyTo(output) }
        }
    } finally {
        file.delete()
    }
}

/** A scratch file in the cache for an export. */
fun AppGraph.scratch(name: String): File = File(context.cacheDir, "scratch/${UUID.randomUUID()}/$name").apply { parentFile?.mkdirs() }
