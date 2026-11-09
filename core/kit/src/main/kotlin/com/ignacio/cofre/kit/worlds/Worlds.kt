package com.ignacio.cofre.kit.worlds

import com.ignacio.cofre.kit.modpacks.FileOps
import com.ignacio.cofre.kit.modpacks.Zip
import com.ignacio.cofre.kit.options.Storage
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

data class World(val folder: String, val name: String, val mode: String, val lastPlayed: Instant?, val gameVersion: String?, val sizeBytes: Long, val iconPNG: ByteArray?) { val id: String get() = folder }
data class WorldBackup(val file: File, val date: Instant, val sizeBytes: Long)

class WorldStore(val gameDirectory: File, private val isGameRunning: () -> Boolean = { false }) {
    data class TrashToken(val world: String, val trashID: String)
    enum class Reason { GameRunning, MissingWorld, BedrockWorld, InvalidArchive }
    class Failure(val reason: Reason) : IllegalStateException(reason.toString())
    private val saves: File get() = FileOps.child(gameDirectory, "saves")
    private val backups: File get() = FileOps.child(gameDirectory, "backups")
    fun list(): List<World> = saves.listFiles().orEmpty().filter { !it.name.startsWith('.') && !Files.isSymbolicLink(it.toPath()) && isWorld(it) }.map(::read).sortedByDescending { it.lastPlayed }
    fun backups(world: String): List<WorldBackup> {
        existing(world)
        return backups.listFiles().orEmpty().filter { it.name.startsWith("$world-") && it.extension == "zip" && !Files.isSymbolicLink(it.toPath()) }.map { WorldBackup(it, Instant.ofEpochMilli(it.lastModified()), it.length()) }.sortedByDescending { it.date }
    }
    fun backup(world: String, date: Instant = Instant.now()): File {
        writable(); val source = existing(world)
        val stamp = date.toString().replace(':', '-')
        var file = FileOps.child(backups, "$world-$stamp.zip")
        if (file.exists()) file = FileOps.child(backups, "$world-$stamp-${UUID.randomUUID().toString().take(8)}.zip")
        Zip.write(source, file); return file
    }
    fun export(world: String, destination: File): File { Zip.write(existing(world), destination); return destination }
    fun import(archive: File, name: String = archive.nameWithoutExtension): World {
        writable(); rejectBedrock(archive)
        val target = folder(name); require(!target.exists()) { "World already exists" }
        val stage = staging()
        try {
            Zip.extract(archive, stage)
            val source = source(stage)
            FileOps.move(source, target)
            return read(target)
        } finally { FileOps.delete(stage) }
    }
    fun restore(archive: File, world: String) {
        writable(); rejectBedrock(archive)
        val target = folder(world); val stage = staging()
        val previous = FileOps.child(saves, ".restore-old-${UUID.randomUUID()}")
        try {
            Zip.extract(archive, stage); val source = source(stage)
            if (target.exists()) { backup(world); FileOps.move(target, previous) }
            try { FileOps.move(source, target) }
            catch (e: Exception) { if (previous.exists()) FileOps.move(previous, target); throw e }
            FileOps.delete(previous)
        } finally { FileOps.delete(stage) }
    }
    fun copy(world: String, destination: WorldStore): World {
        writable(); destination.writable()
        val source = existing(world); val target = destination.folder(world)
        require(!target.exists()) { "World already exists" }
        val stage = destination.staging()
        try {
            val copied = File(stage, "world"); FileOps.copy(source, copied); FileOps.move(copied, target); return destination.read(target)
        } finally { FileOps.delete(stage) }
    }
    fun trash(world: String): TrashToken {
        writable(); val source = existing(world); val token = TrashToken(world, UUID.randomUUID().toString())
        FileOps.move(source, trashFolder(token)); trashFolder(token).setLastModified(System.currentTimeMillis()) // the trash date, for purging
        return token
    }
    fun undo(token: TrashToken) { writable(); FileOps.move(trashFolder(token), folder(token.world)) }
    private fun trashFolder(token: TrashToken): File { require(token.trashID.matches(Regex("[a-f0-9-]{36}"))); return FileOps.child(gameDirectory, ".cofre-trash/worlds/${token.trashID}") }
    private fun folder(name: String): File { require(name.isNotBlank() && '/' !in name && !name.startsWith('.')); return FileOps.child(saves, name) }
    private fun existing(name: String): File = folder(name).also { if (!isWorld(it)) throw Failure(Reason.MissingWorld) }
    private fun staging(): File = FileOps.child(saves, ".restore-${UUID.randomUUID()}").also { Files.createDirectories(it.toPath()) }
    private fun rejectBedrock(archive: File) { if (archive.extension.equals("mcworld", true)) throw Failure(Reason.BedrockWorld) }
    private fun writable() { if (isGameRunning()) throw Failure(Reason.GameRunning) }
    private fun isWorld(dir: File): Boolean = dir.isDirectory && listOf("level.dat", "level.dat_old").any { name -> FileOps.child(dir, name).isFile }
    private fun source(stage: File): File {
        if (isWorld(stage)) return stage
        return stage.listFiles().orEmpty().filter { it.isDirectory && isWorld(it) }.singleOrNull() ?: throw Failure(Reason.InvalidArchive)
    }
    private fun read(dir: File): World {
        val root = listOf("level.dat", "level.dat_old").firstNotNullOfOrNull { name ->
            runCatching { val f = FileOps.child(dir, name); require(f.length() <= NBT.MAX_BYTES); NBT.read(f.readBytes()).root }.getOrNull()
        }?.let { (it["Data"] as? NBTTag.Compound) ?: it }
        val mode = root?.get("GameType")?.integer?.toInt() ?: 0
        val icon = FileOps.child(dir, "icon.png").takeIf { it.isFile && it.length() <= 1 shl 20 }?.readBytes()
        return World(dir.name, root?.get("LevelName")?.string ?: dir.name, listOf("Survival", "Creative", "Adventure", "Spectator").getOrElse(mode) { "Unknown" }, root?.get("LastPlayed")?.integer?.let(Instant::ofEpochMilli), (root?.get("Version") as? NBTTag.Compound)?.get("Name")?.string, Storage.bytes(dir), icon)
    }
}
