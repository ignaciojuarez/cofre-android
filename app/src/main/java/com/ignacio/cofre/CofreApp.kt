package com.ignacio.cofre

import android.app.Application
import com.ignacio.cofre.kit.modpacks.PackStage

class CofreApp : Application() {
    /** Built in the main process only: `:game` and `:installer` never touch accounts, the vault or instances. */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        if (getProcessName() != packageName) return
        graph = AppGraph(this)
        // Disk housekeeping, once per start: pack imports a kill left half-done, and trash older than 30 days.
        graph.work({}) {
            graph.onIo {
                PackStage.cleanupAbandoned(graph.instances.root)
                graph.instances.purgeOldTrash()
            }
        }
    }
}
