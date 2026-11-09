package com.ignacio.cofre.files

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.ignacio.cofre.R
import java.io.File
import java.io.FileNotFoundException

/**
 * Shows `filesDir/instances` in the system file picker and file managers, so mods, worlds and resource packs can
 * be dropped in from anywhere. Document IDs are paths relative to that folder ("" is the root).
 */
class InstancesProvider : DocumentsProvider() {
    private val root: File get() = File(requireNotNull(context).filesDir, "instances").apply { mkdirs() }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        val context = requireNotNull(context)
        newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_DOCUMENT_ID, "")
            .add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
            .add(Root.COLUMN_SUMMARY, context.getString(R.string.files_root_summary))
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            .add(Root.COLUMN_AVAILABLE_BYTES, root.usableSpace)
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addFile(file(documentId), documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            file(parentDocumentId).listFiles().orEmpty()
                .filter { !it.name.startsWith(".") }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .forEach { addFile(it, id(it)) }
        }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = file(documentId)
        if (file.isDirectory) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = file(parentDocumentId)
        require(parent.isDirectory && '/' !in displayName && !displayName.startsWith("."))
        val stem = displayName.substringBeforeLast('.')
        val extension = displayName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var target = File(parent, displayName)
        var n = 1
        while (target.exists()) target = File(parent, "$stem (${n++})$extension")
        val made = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!made) throw FileNotFoundException(displayName)
        return id(target)
    }

    override fun deleteDocument(documentId: String) {
        val file = file(documentId)
        require(file != root) { "The instances folder can't be deleted" }
        if (!file.deleteRecursively()) throw FileNotFoundException(documentId)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId.isEmpty() || documentId.startsWith("$parentDocumentId/")

    override fun getDocumentType(documentId: String): String = mime(file(documentId))

    /** The file for [id], refusing anything that resolves outside the instances folder. */
    private fun file(id: String): File {
        val base = root.canonicalFile
        val file = if (id.isEmpty()) base else File(base, id).canonicalFile
        if (file != base && !file.path.startsWith(base.path + File.separator)) throw FileNotFoundException(id)
        if (!file.exists()) throw FileNotFoundException(id)
        return file
    }

    private fun id(file: File): String = file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath

    private fun MatrixCursor.addFile(file: File, id: String) {
        val dir = file.isDirectory
        var flags = if (dir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        if (id.isNotEmpty()) flags = flags or Document.FLAG_SUPPORTS_DELETE
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, if (id.isEmpty()) requireNotNull(context).getString(R.string.files_root_title) else file.name)
            .add(Document.COLUMN_MIME_TYPE, mime(file))
            .add(Document.COLUMN_SIZE, if (dir) null else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    private fun mime(file: File): String = when {
        file.isDirectory -> Document.MIME_TYPE_DIR
        file.extension == "jar" -> "application/java-archive"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    }

    private companion object {
        const val ROOT_ID = "instances"
        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS,
            Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES,
        )
        val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
