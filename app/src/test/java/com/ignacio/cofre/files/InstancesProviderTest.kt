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
    }
}
