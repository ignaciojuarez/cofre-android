package com.ignacio.cofre

import org.junit.Test
import java.nio.file.Files
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SecretVaultTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun secretsSurviveAReopenAndNeverSitInPlainText() {
        val file = Files.createTempDirectory("vault").resolve("secrets.bin").toFile()
        val key = key()
        SecretVault(file) { key }.apply {
            set("account.a.refresh", "M.R3_BAY.secret-refresh-token")
            set("curseforge.key", "cf-key")
        }
        val reopened = SecretVault(file) { key }
        assertEquals("M.R3_BAY.secret-refresh-token", reopened.get("account.a.refresh"))
        assertFalse(file.readBytes().decodeToString().contains("secret-refresh-token"))
        reopened.removeAll("account.a.")
        assertNull(SecretVault(file) { key }.get("account.a.refresh"))
        assertEquals("cf-key", SecretVault(file) { key }.get("curseforge.key"))
    }

    @Test fun aFileTheKeyCannotOpenIsMovedAsideNotOverwritten() {
        val file = Files.createTempDirectory("vault").resolve("secrets.bin").toFile()
        SecretVault(file) { key() }.set("token", "x")
        val old = file.readBytes()
        val vault = SecretVault(file) { key() }
        assertNull(vault.get("token"))
        val aside = file.parentFile!!.listFiles().orEmpty().single { it.name.startsWith("secrets.bin.bad-") }
        assertContentEquals(old, aside.readBytes())
        vault.set("token", "y")
        assertEquals("y", vault.get("token"))
    }

    @Test fun aTransientKeystoreFailureNeitherCachesNorOverwrites() {
        val file = Files.createTempDirectory("vault").resolve("secrets.bin").toFile()
        val key = key()
        SecretVault(file) { key }.set("token", "x")
        val old = file.readBytes()
        var ready = false
        val vault = SecretVault(file) { if (ready) key else throw KeyStoreException("not unlocked yet") }
        assertNull(vault.get("token"))
        assertFailsWith<KeyStoreException> { vault.set("other", "y") }
        assertContentEquals(old, file.readBytes())
        ready = true
        assertEquals("x", vault.get("token"))
    }
}
