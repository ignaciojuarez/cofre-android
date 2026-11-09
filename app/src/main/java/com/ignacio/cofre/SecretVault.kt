package com.ignacio.cofre

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.modpacks.FileOps
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets (account tokens, the CurseForge key) as one AES-GCM-encrypted JSON map in [file]. The key lives in
 * Android Keystore and never leaves it. A file the key definitely can't open (restored on another device, key wiped)
 * is moved aside to `secrets.bin.bad-<time>` and the vault starts empty: accounts then show "signed out". Any other
 * failure (Keystore not ready after boot) is not cached: [get] reads as missing, and writes fail rather than
 * overwrite secrets that may still be readable later.
 */
class SecretVault(private val file: File, private val key: () -> SecretKey = ::keystoreKey) {
    private val lock = Any()
    private var cache: MutableMap<String, String>? = null

    fun get(name: String): String? = synchronized(lock) {
        try { load()[name] } catch (_: GeneralSecurityException) { null } // transient: try again next time
    }

    fun set(name: String, value: String?) = synchronized(lock) {
        val map = load()
        if (value == null) map.remove(name) else map[name] = value
        write(map)
    }

    /** Removes every entry whose name starts with [prefix]. */
    fun removeAll(prefix: String) = synchronized(lock) {
        val map = load()
        if (map.keys.removeAll { it.startsWith(prefix) }) write(map)
    }

    private fun load(): MutableMap<String, String> = cache ?: run {
        val read = if (!file.isFile) mutableMapOf() else try {
            decrypt(file.readBytes())
        } catch (_: AEADBadTagException) {
            file.renameTo(File(file.parentFile, "${file.name}.bad-${System.currentTimeMillis()}"))
            mutableMapOf()
        }
        read.also { cache = it }
    }

    private fun write(map: Map<String, String>) {
        cache = map.toMutableMap()
        FileOps.atomic(file, encrypt(Kit.json.encodeToString(map).toByteArray()))
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): MutableMap<String, String> {
        val ivSize = data[0].toInt()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 1, ivSize))
        val plain = cipher.doFinal(data, 1 + ivSize, data.size - 1 - ivSize)
        return Kit.json.decodeFromString<Map<String, String>>(plain.decodeToString()).toMutableMap()
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ALIAS = "cofre.secrets"

        /** Cofre's AES-256-GCM key in Android Keystore, made on first use. */
        fun keystoreKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
        }
    }
}
