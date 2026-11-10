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
import com.ignacio.cofre.kit.instances.InstanceStore
import com.ignacio.cofre.kit.common.FileOps
import java.io.File
import java.nio.file.Files
import java.io.FileNotFoundException

/**
 * Shows `filesDir/instances` and `filesDir/mod-folders` in the system file picker and file managers, so mods, worlds
 * and resource packs can be dropped in from anywhere. Document IDs are `root` (instances) or `folders` (mod folders),
 * then `/<path relative to that folder>`. An instance's links to its mod folder are not listed and can't be followed:
 * its mods are reached through Mod folders.
 */
class InstancesProvider : DocumentsProvider() {
    private fun base(rootDoc: String): File =
        File(requireNotNull(context).filesDir, if (rootDoc == FOLDERS_DOC) "mod-folders" else "instances").apply { mkdirs() }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        val context = requireNotNull(context)
        for ((rootId, doc, summary) in listOf(Triple(ROOT_ID, ROOT_DOC, R.string.files_root_summary), Triple(FOLDERS_ID, FOLDERS_DOC, R.string.folders_root_summary))) {
            newRow()
                .add(Root.COLUMN_ROOT_ID, rootId)
                .add(Root.COLUMN_DOCUMENT_ID, doc)
                .add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
                .add(Root.COLUMN_SUMMARY, context.getString(summary))
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                .add(Root.COLUMN_AVAILABLE_BYTES, base(doc).usableSpace)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addFile(file(documentId), documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            file(parentDocumentId).listFiles().orEmpty()
                .filter { !it.name.startsWith(".") && !Files.isSymbolicLink(it.toPath()) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .forEach { addFile(it, "$parentDocumentId/${it.name}") }
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
        return "$parentDocumentId/${target.name}"
    }

    override fun deleteDocument(documentId: String) {
        require(documentId != ROOT_DOC && documentId != FOLDERS_DOC) { "The top folders can't be deleted" }
        val file = file(documentId)
        // Never follows a link out of the instances folder.
        try { FileOps.delete(file) } catch (e: java.io.IOException) { throw FileNotFoundException("$documentId: $e") }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith("$parentDocumentId/") && runCatching { file(documentId) }.isSuccess

    override fun getDocumentType(documentId: String): String = mime(file(documentId))

    /**
     * The file for [id], refusing anything that resolves outside its top folder, links included. IDs are plain paths:
     * an empty, `.` or `..` segment is refused, so a grant for `root/A` can't reach `root/A/../B` through
     * [isChildDocument].
     */
    private fun file(id: String): File {
        val top = id.substringBefore('/')
        if (top != ROOT_DOC && top != FOLDERS_DOC) throw FileNotFoundException(id)
        val base = base(top).canonicalFile
        if (id.split('/').any { it.isEmpty() || it == "." || it == ".." }) throw FileNotFoundException(id)
        val file = if (id == top) base else File(base, id.removePrefix("$top/")).canonicalFile
        if (file != base && !file.path.startsWith(base.path + File.separator)) throw FileNotFoundException(id)
        // Cofre's own folders (trash, temp writes) stay private even when a caller knows the path.
        if (file.relativeTo(base).invariantSeparatorsPath.split('/').any(InstanceStore::isPrivate)) throw FileNotFoundException(id)
        if (!file.exists()) throw FileNotFoundException(id)
        return file
    }

    private fun MatrixCursor.addFile(file: File, id: String) {
        val dir = file.isDirectory
        var flags = if (dir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        val top = id == ROOT_DOC || id == FOLDERS_DOC
        if (!top) flags = flags or Document.FLAG_SUPPORTS_DELETE
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, when (id) {
                ROOT_DOC -> requireNotNull(context).getString(R.string.files_root_title)
                FOLDERS_DOC -> requireNotNull(context).getString(R.string.folders_root_title)
                else -> file.name
            })
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
        const val ROOT_DOC = "root"
        const val FOLDERS_ID = "mod-folders"
        const val FOLDERS_DOC = "folders"
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
