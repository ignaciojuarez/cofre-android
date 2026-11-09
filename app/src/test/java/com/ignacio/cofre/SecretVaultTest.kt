package com.ignacio.cofre

import org.junit.Test
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.assertEquals
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

    @Test fun aFileTheKeyCannotOpenReadsAsEmpty() {
        val file = Files.createTempDirectory("vault").resolve("secrets.bin").toFile()
        SecretVault(file) { key() }.set("token", "x")
        assertNull(SecretVault(file) { key() }.get("token"))
    }
}
