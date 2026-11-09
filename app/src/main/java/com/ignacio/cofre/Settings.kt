package com.ignacio.cofre

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ignacio.cofre.kit.common.Kit
import kotlinx.serialization.Serializable
import java.io.File

/** Launcher preferences. Per-instance settings live in each instance's cofre.json. */
@Serializable
data class Settings(
    val setupDone: Boolean = false,
    /** Odin firmware swaps A/B in some modes; this swaps them back. */
    val swapConfirmBack: Boolean = false,
    val immersive: Boolean = false,
    /** Default heap for instances set to Auto: the RAM-tier pick, or [memoryMB]. */
    val autoMemory: Boolean = true,
    val memoryMB: Int = 4096,
    val selectedAccount: String? = null,
    val activeInstance: String? = null,
    /** Big downloads on mobile data start without asking. */
    val allowMetered: Boolean = false,
)

/** [Settings] as observable state, saved to a small JSON file on every change (the `@AppStorage` equivalent). */
class SettingsStore(private val file: File) {
    var value: Settings by mutableStateOf(read())
        private set

    fun update(transform: (Settings) -> Settings) {
        val next = transform(value)
        if (next == value) return
        value = next
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(Kit.json.encodeToString(next))
        temp.renameTo(file)
    }

    private fun read(): Settings =
        runCatching { Kit.json.decodeFromString<Settings>(file.readText()) }.getOrNull() ?: Settings()
}
