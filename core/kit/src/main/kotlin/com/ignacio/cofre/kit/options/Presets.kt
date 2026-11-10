package com.ignacio.cofre.kit.options

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.downloads.DownloadItem
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

@Serializable
data class CofrePreset(val id: String, val name: String, val pitch: String, val gameVersion: String, val loader: Loader, val loaderVersion: String, val renderer: String, val status: String, val pinnedMods: List<Mod>, val firstCreateOptions: Map<String, Map<String, String>>, val sourceDate: String) {
    @Serializable
    data class Mod(val project: String, val slug: String, val version: String, val file: String, val sha512: String, val license: String, val url: String)
    fun options(physicalMB: Int): Map<String, String> = firstCreateOptions.getValue(if (physicalMB >= 6 * 1024 * 9 / 10) "high" else "low")
    fun validate() {
        require(id.isNotBlank() && name.isNotBlank() && pitch.isNotBlank())
        require(gameVersion == Target.GAME_VERSION && loader == Target.LOADER && renderer == "vulkan" && loaderVersion.isNotBlank())
        require(firstCreateOptions.keys.containsAll(listOf("low", "high")))
        require(pinnedMods.map { it.project }.distinct().size == pinnedMods.size)
        for (m in pinnedMods) {
            require(m.project.isNotBlank() && m.slug.isNotBlank() && m.version.isNotBlank() && m.license.isNotBlank())
            require(m.file.endsWith(".jar") && '/' !in m.file && '\\' !in m.file)
            require(m.sha512.matches(Regex("[a-fA-F0-9]{128}")))
            require(m.url.toHttpUrlOrNull()?.let { it.isHttps && it.username.isEmpty() && it.password.isEmpty() } == true)
        }
    }
}

object PresetCatalog {
    @Serializable data class Manifest(val schema: Int, val presets: List<CofrePreset>)
    fun decode(text: String): Manifest = Kit.json.decodeFromString<Manifest>(text).also(::validate)
    fun validate(manifest: Manifest) {
        require(manifest.schema == 1 && manifest.presets.map { it.id }.distinct().size == manifest.presets.size)
        manifest.presets.forEach { it.validate() }
    }

    /**
     * A new instance's first files in [gameDirectory]: [preset]'s options (none for a plain instance), Cofre's
     * defaults for [physicalMB], Vulkan, and NeoForge's GL early window off. Returns the preset's pinned mods,
     * hash-checked downloads into [mods] (the instance's mod folder's `mods/`).
     */
    fun apply(preset: CofrePreset?, gameDirectory: File, physicalMB: Int, mods: File = File(gameDirectory, "mods")): List<DownloadItem> {
        val options = GameOptions(gameDirectory)
        if (preset != null) options.seedPreset(preset, physicalMB)
        options.seedDefaults(physicalMB)
        options.useVulkan()
        FMLConfig.disableEarlyWindow(gameDirectory)
        return preset?.pinnedMods.orEmpty().map { DownloadItem(it.url, File(mods, it.file).path, title = it.file, sha512 = it.sha512) }
    }
    val presets: List<CofrePreset> by lazy { decode(requireNotNull(javaClass.getResourceAsStream("/presets.json")).bufferedReader().use { it.readText() }).presets }
}
