package com.ignacio.cofre

import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Opens the system share sheet for [file] (served from the cache through Cofre's FileProvider). */
fun AppGraph.share(file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
