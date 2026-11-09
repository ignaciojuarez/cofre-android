package com.ignacio.cofre

import android.content.Context
import com.ignacio.cofre.kit.downloads.DownloadQueue
import com.ignacio.cofre.kit.instances.InstanceStore
import com.ignacio.cofre.kit.modrinth.Modrinth
import java.io.File

/**
 * Everything long-lived, built once in [CofreApp]: the SwiftUI `@Environment` equivalent, passed down explicitly.
 * Add a `val` here when a screen needs a new long-lived object; keep construction cheap (lazy for anything heavy).
 */
class AppGraph(context: Context) {
    val home: File = context.filesDir
    val instances = InstanceStore(File(home, "instances").apply { mkdirs() })
    val modrinth = Modrinth()
    val downloads by lazy { DownloadQueue(File(home, "downloads/queue.json").apply { parentFile?.mkdirs() }) }
}
