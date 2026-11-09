package com.ignacio.cofre.ui.mods

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.content.ContentStore
import com.ignacio.cofre.kit.mods.InstalledMod
import com.ignacio.cofre.kit.mods.ModCandidate
import com.ignacio.cofre.kit.mods.ModInstallPlan
import com.ignacio.cofre.kit.mods.VersionChannel
import com.ignacio.cofre.kit.modpacks.Modpack
import com.ignacio.cofre.kit.modpacks.PackFile
import com.ignacio.cofre.kit.modpacks.Modpacks
import com.ignacio.cofre.kit.pages.ProjectPage
import java.io.File

/** Sample data for screenshots: real NeoForge 26.2 projects, no network. */
internal object ModsSamples {
    private fun p(id: String, slug: String, title: String, summary: String, downloads: Long, kind: ContentKind = ContentKind.Mods) =
        ProjectSummary(id, ProjectPage.Source.Modrinth, kind, title, summary, "https://cdn.modrinth.com/data/$id/icon.png", downloads, slug, "https://modrinth.com/mod/$slug")

    val sodium = p("AANobbMI", "sodium", "Sodium", "The fastest and most compatible rendering optimization mod for Minecraft.", 112_400_000)
    val results = listOf(
        sodium,
        p("NNAgCjsB", "entityculling", "Entity Culling", "Using async path-tracing to hide block and entity models that are not visible.", 41_300_000),
        p("uXXizFIs", "ferrite-core", "FerriteCore", "Memory usage optimizations.", 63_200_000),
        p("5ZwdcRci", "immediatelyfast", "ImmediatelyFast", "Speed up immediate mode rendering in Minecraft.", 38_900_000),
        p("YL57xq9U", "iris", "Iris Shaders", "A modern shader pack loader for Minecraft intended to be compatible with existing OptiFine shader packs.", 98_100_000),
        p("uCdwusMi", "distanthorizons", "Distant Horizons", "See farther without turning your game into a slide show.", 22_500_000),
        p("DOUdJVEm", "controlify", "Controlify", "The most advanced controller mod for Minecraft.", 9_400_000),
        p("nmDcB62a", "modernfix", "ModernFix", "All-in-one mod that improves performance, reduces memory usage, and fixes many bugs.", 30_100_000),
    )

    val browse = BrowseState(query = BrowseQuery("sodium"), results = results)

    val page = ProjectPage(
        sodium.id, ProjectPage.Source.Modrinth, sodium.title, sodium.summary, sodium.iconUrl,
        ProjectPage.Body.Markdown(
            """
            Sodium is a powerful rendering engine for Minecraft which greatly improves frame rates and stuttering, while fixing many graphical issues.

            ## Features

            * **Faster** chunk rendering, with *no* visual changes
            * Works with most mods, and with [Controlify](https://modrinth.com/mod/controlify)
            * Settings live in `Video Settings → Performance`

            | Device | Before | After |
            |---|---:|---:|
            | Odin 3 | 60 fps | 120 fps |

            > Sodium doesn't add shaders. Iris needs OpenGL, so it can't run on Vulkan.
            """.trimIndent(),
        ),
        gallery = listOf(
            ProjectPage.Image("1", "https://cdn.modrinth.com/a_350.webp", title = "Grand Teton", featured = true),
            ProjectPage.Image("2", "https://cdn.modrinth.com/b_350.webp", title = "A nice sunset"),
        ),
        links = listOf(ProjectPage.Link("source", "https://github.com/CaffeineMC/sodium"), ProjectPage.Link("issues", "https://github.com/CaffeineMC/sodium/issues")),
        license = "Polyform Shield",
        versions = listOf(
            ProjectPage.Version("v3", "Sodium 0.9.2 for NeoForge 26.2", listOf("26.2"), listOf("neoforge"), "release"),
            ProjectPage.Version("v2", "Sodium 0.9.1 for NeoForge 26.2", listOf("26.2"), listOf("neoforge"), "beta"),
            ProjectPage.Version("v1", "Sodium 0.9.2 for Fabric 26.2", listOf("26.2"), listOf("fabric"), "release"),
        ),
    )

    val project = ProjectState(sodium, page, loading = false)

    private fun candidate(id: String, title: String, size: Long, channel: VersionChannel = VersionChannel.Release) =
        ModCandidate(id, "$id-v", title, "1.0.0", listOf("26.2"), listOf(Loader.NeoForge), channel, filename = "$id.jar", url = "https://cdn/$id.jar", size = size)

    val confirm = InstallPhase.Confirm(
        ModInstallPlan(
            candidate("create", "Create", 15_800_000),
            required = listOf(candidate("flywheel", "Flywheel", 1_900_000), candidate("ponder", "Ponder", 640_000, VersionChannel.Beta)),
            optional = listOf(candidate("jei", "Just Enough Items", 1_200_000)),
            present = listOf("Sodium"),
        ),
        offered = listOf(candidate("jei", "Just Enough Items", 1_200_000)),
    )
    val create = ProjectSummary("LNytGWDc", ProjectPage.Source.Modrinth, ContentKind.Mods, "Create", "Aesthetic technology")

    private fun item(file: String, name: String, version: String, enabled: Boolean = true, kind: ContentKind = ContentKind.Mods, world: String? = null) =
        ContentStore.Item(if (world == null) file else "$world/$file", name, version, world, kind, enabled, inUse = false)

    val installed = InstalledState(
        rows = listOf(
            InstalledRow(item("sodium-neoforge-0.9.2.jar", "Sodium", "mc26.2-0.9.2-neoforge"), InstalledMod("sodium-neoforge-0.9.2.jar", "Sodium", "AANobbMI", "v3")),
            InstalledRow(
                item("ferritecore-9.0.0-neoforge.jar", "FerriteCore", "9.0.0-neoforge"), null,
                update = candidate("uXXizFIs", "FerriteCore", 120_000).copy(version = "9.0.1-neoforge"),
            ),
            InstalledRow(
                item("glwidgets-1.2.jar", "GL Widgets", "1.2"), null,
                badge = "Needs OpenGL", reason = "This mod calls OpenGL, so it will crash on Vulkan.",
            ),
            InstalledRow(item("controlify-3.5.3.jar", "Controlify", "3.5.3+mc26.2", enabled = false)),
        ),
        loading = false,
    )

    val ready: ImportPhase.Ready = run {
        val pack = Modpack(
            Modpack.Format.Modrinth, "Handheld Plus", "26.2", Loader.NeoForge, "26.2.0.93",
            List(42) { PackFile("mods/mod$it.jar") } + List(3) { PackFile("resourcepacks/pack$it.zip") },
            List(18) { Modpack.Override("overrides/config/c$it.toml", "config/c$it.toml", 100) },
            312_000_000,
        )
        ImportPhase.Ready(File("handheld-plus.mrpack"), pack, Modpacks.plan(pack), null)
    }
}
