package com.ignacio.cofre.files

import android.Manifest
import android.content.Context
import android.content.pm.ProviderInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Paths
import android.provider.DocumentsContract.Document
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstancesProviderTest {
    @Test fun aGrantForOneFolderDoesNotReachItsSiblings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("a", "b").forEach { File(context.filesDir, "instances/$it").mkdirs() }
        val info = ProviderInfo().apply {
            authority = "${context.packageName}.instances"
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        }
        val provider = Robolectric.buildContentProvider(InstancesProvider::class.java).create(info).get()
        assertFalse(provider.isChildDocument("root/a", "root/a/../b"))
        assertFalse(provider.isChildDocument("root/a", "root/a/./../b"))
        assertFailsWith<FileNotFoundException> { provider.queryDocument("root/a/../b", null) }
        assertFailsWith<FileNotFoundException> { provider.queryDocument("root//b", null) }
        File(context.filesDir, "instances/a/mods").mkdirs()
        assertTrue(provider.isChildDocument("root/a", "root/a/mods"))

        // An instance's link to its mod folder isn't listed or followed; the folder is reached through Mod folders.
        val folder = File(context.filesDir, "mod-folders/f/resourcepacks").apply { mkdirs() }
        File(folder, "pack.zip").writeText("x")
        Files.createSymbolicLink(File(context.filesDir, "instances/a/resourcepacks").toPath(), Paths.get("../../mod-folders/f/resourcepacks"))
        val names = provider.queryChildDocuments("root/a", arrayOf(Document.COLUMN_DOCUMENT_ID), null as String?).use { c -> List(c.count) { c.moveToPosition(it); c.getString(0) } }
        assertEquals(listOf("root/a/mods"), names)
        assertFailsWith<FileNotFoundException> { provider.queryDocument("root/a/resourcepacks/pack.zip", null) }
        assertFalse(provider.isChildDocument("root/a", "root/a/resourcepacks"))
        assertTrue(provider.isChildDocument("folders/f", "folders/f/resourcepacks/pack.zip"))
        assertFailsWith<FileNotFoundException> { provider.queryDocument("folders/../instances", null) }
    }
}
