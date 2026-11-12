package com.ignacio.cofre

import android.app.Application
import com.ignacio.cofre.kit.instances.InstanceStore
import com.ignacio.cofre.kit.modfolders.ModFolderMigration
import com.ignacio.cofre.kit.modpacks.PackStage
import com.ignacio.cofre.kit.mods.ModInstaller
import com.ignacio.cofre.kit.mods.Snapshots
import kotlinx.coroutines.sync.withLock
import org.minidns.dnsserverlookup.android21.AndroidUsingLinkProperties

class CofreApp : Application() {
    /** Built in the main process only: `:game` and `:installer` never touch accounts, the vault or instances. */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        if (getProcessName() != packageName) return
        // Server SRV lookups ask the active network's DNS servers: Android has no resolv.conf for MiniDNS to read.
        AndroidUsingLinkProperties.setup(this)
        graph = AppGraph(this)
        // Disk housekeeping, once per start: mod installs and restores a kill cut short are put back, instances still
        // holding their own mods move to mod folders, pack imports a kill left half-done go, and trash older than 30 days.
        // A Cofre update downloaded by an earlier run goes too (installed, it's this run).
        graph.work({}) {
            val dirs = graph.onIo {
                graph.updates.clean()
                PackStage.cleanupAbandoned(graph.instances.root)
                graph.instances.list().map(graph.instances::directory) + graph.modFolders.list().map(graph.modFolders::directory)
            }
            for (dir in dirs) InstanceStore.lock(dir).withLock { graph.onIo { runCatching { ModInstaller.recover(dir); Snapshots.recover(dir) } } }
            val moved = graph.onIo {
                ModFolderMigration.run(graph.instances, graph.modFolders).also {
                    graph.instances.purgeOldTrash()
                    graph.modFolders.purgeOldTrash()
                }
            }
            if (moved > 0) graph.library.refresh()
        }
    }
}
