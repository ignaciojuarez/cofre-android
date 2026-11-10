package com.ignacio.cofre

import android.app.Application
import com.ignacio.cofre.kit.modfolders.ModFolderMigration
import com.ignacio.cofre.kit.modpacks.PackStage

class CofreApp : Application() {
    /** Built in the main process only: `:game` and `:installer` never touch accounts, the vault or instances. */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        if (getProcessName() != packageName) return
        graph = AppGraph(this)
        // Disk housekeeping, once per start: instances still holding their own mods move to mod folders, pack
        // imports a kill left half-done go, and trash older than 30 days.
        graph.work({}) {
            val moved = graph.onIo {
                PackStage.cleanupAbandoned(graph.instances.root)
                ModFolderMigration.run(graph.instances, graph.modFolders).also {
                    graph.instances.purgeOldTrash()
                    graph.modFolders.purgeOldTrash()
                }
            }
            if (moved > 0) graph.library.refresh()
        }
    }
}
