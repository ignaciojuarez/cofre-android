package com.ignacio.cofre.kit.worlds

import com.ignacio.cofre.kit.modpacks.Zip
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class WorldsTest {
    @TempDir lateinit var root: File
    private fun document(name: String = "My Base"): NBTDocument = NBTDocument(NBTTag.Compound(mapOf("Data" to NBTTag.Compound(linkedMapOf(
        "LevelName" to NBTTag.StringTag(name), "GameType" to NBTTag.IntTag(1), "LastPlayed" to NBTTag.LongTag(1700000000000), "Version" to NBTTag.Compound(mapOf("Name" to NBTTag.StringTag("26.2"))),
        "byte" to NBTTag.ByteTag(-1), "short" to NBTTag.ShortTag(-123), "float" to NBTTag.FloatTag(1.5f), "double" to NBTTag.DoubleTag(2.5), "bytes" to NBTTag.ByteArrayTag(byteArrayOf(-1, 2)),
        "strings" to NBTTag.ListTag(8, listOf(NBTTag.StringTag("vanilla"), NBTTag.StringTag("neoforge"))), "empty" to NBTTag.ListTag(0, emptyList()), "ints" to NBTTag.IntArrayTag(intArrayOf(-1, 2)), "longs" to NBTTag.LongArrayTag(longArrayOf(-1, 2))
    )))), "root")
    private fun world(name: String, game: File = root, level: String = "level.dat"): File = File(game, "saves/$name").apply { mkdirs(); File(this, level).writeBytes(NBT.write(document(name.uppercase()), gzip = true)) }
    @Test fun nbtRoundTripsEveryTypePlainAndGzip() {
        val doc = document(); for (gzip in listOf(false, true)) { val encoded = NBT.write(doc, gzip); assertEquals(doc, NBT.read(encoded)); if (!gzip) assertContentEquals(encoded, NBT.write(NBT.read(encoded))) }
    }
    @Test fun nbtKeepsUnknownNamedTagsWhenKnownValueChanges() {
        val doc = document(); val data = doc.root["Data"] as NBTTag.Compound
        val changed = NBTDocument(NBTTag.Compound(doc.root.value + ("Data" to NBTTag.Compound(data.value + ("LevelName" to NBTTag.StringTag("New"))))), doc.name)
        assertEquals(data["longs"], (NBT.read(NBT.write(changed)).root["Data"] as NBTTag.Compound)["longs"])
    }
    @Test fun largeArraysStayPrimitive() {
        val longs = LongArray(1_000_000) { it * 7L }
        val tag = NBT.read(NBT.write(NBTDocument(NBTTag.Compound(mapOf("heightmap" to NBTTag.LongArrayTag(longs)))))).root["heightmap"]
        assertContentEquals(longs, assertIs<NBTTag.LongArrayTag>(tag).value)
    }
    @Test fun nbtSupportsJavaModifiedUTF8AndSignedIntegers() {
        val doc = NBTDocument(NBTTag.Compound(mapOf("😀\u0000" to NBTTag.StringTag("𐐀\u0000"), "i" to NBTTag.ShortTag(-321))))
        val decoded = NBT.read(NBT.write(doc)); assertEquals(doc, decoded); assertEquals(-321L, decoded.root["i"]?.integer)
    }
    @Test fun truncatedNBTAndGzipAreRejected() {
        for (gzip in listOf(false, true)) { val b = NBT.write(document(), gzip); for (size in listOf(0, 1, b.size / 2, b.size - 1)) assertFails { NBT.read(b.copyOf(size)) } }
    }
    @Test fun invalidNBTTagCountsAndTrailingBytesFail() {
        assertFails { NBT.read(byteArrayOf(10, 0, 0, 13, 0, 0, 0)) }
        assertFails { NBT.read(byteArrayOf(10, 0, 0, 7, 0, 0, 127, 127, 127, 127, 0)) }
        assertFails { NBT.read(NBT.write(document()) + byteArrayOf(1)) }
        assertFails { NBT.write(NBTDocument(NBTTag.Compound(mapOf("bad" to NBTTag.ListTag(3, listOf(NBTTag.StringTag("bad"))))))) }
    }
    @Test fun nestingLimitIsCheckedBothWays() {
        var t: NBTTag = NBTTag.StringTag("x"); repeat(70) { t = NBTTag.Compound(mapOf("a" to t)) }
        assertFails { NBT.write(NBTDocument(t as NBTTag.Compound)) }
    }
    @Test fun worldMetadataListsAndBadFilesUseFolderName() {
        world("good"); File(root, "saves/garbage").mkdirs(); File(root, "saves/garbage/level.dat").writeBytes(byteArrayOf(1, 2)); File(root, "saves/stray").mkdir()
        val all = WorldStore(root).list(); assertEquals(setOf("good", "garbage"), all.map { it.folder }.toSet())
        val good = all.first { it.folder == "good" }; assertEquals("GOOD", good.name); assertEquals("Creative", good.mode); assertEquals("26.2", good.gameVersion); assertNotNull(good.lastPlayed)
        assertEquals("garbage", all.first { it.folder == "garbage" }.name)
    }
    @Test fun fallbackLevelDatOldAndStableIDs() {
        world("Recovered", level = "level.dat_old"); File(root, "saves/Recovered/level.dat").writeText("bad")
        val s = WorldStore(root); assertEquals("RECOVERED", s.list().single().name); assertEquals(s.list().map { it.id }, s.list().map { it.id })
    }
    @Test fun worldFoldersAreIsolatedByConstructorRoot() {
        val a = File(root, "a"); val b = File(root, "b"); world("One", a); world("Two", b)
        assertEquals("One", WorldStore(a).list().single().id); assertEquals("Two", WorldStore(b).list().single().id)
    }
    @Test fun backupRestoreBacksUpCurrentWorldBeforeReplacing() {
        val dir = world("Main"); val s = WorldStore(root); File(dir, "marker").writeText("old")
        val backup = s.backup("Main", Instant.parse("2026-11-08T12:34:56Z")); assertTrue(backup.name.startsWith("Main-2026-11-08T12-34-56Z"))
        File(dir, "marker").writeText("new"); s.restore(backup, "Main")
        assertEquals("old", File(dir, "marker").readText()); assertEquals(2, s.backups("Main").size)
        val savedCurrent = s.backups("Main").first { it.file != backup }; assertEquals("new", Zip.read(savedCurrent.file, "marker").decodeToString())
    }
    @Test fun backupsBelongOnlyToTheirOwnWorld() {
        world("survival"); world("survival-hardcore"); val s = WorldStore(root); val at = Instant.parse("2026-11-08T12:34:56.789Z")
        val mine = s.backup("survival", at); val again = s.backup("survival", at); val other = s.backup("survival-hardcore", at)
        assertEquals(setOf(mine, again), s.backups("survival").map { it.file }.toSet()); assertEquals(listOf(other), s.backups("survival-hardcore").map { it.file })
    }
    @Test fun invalidRestoreLeavesWorldAndNoSpuriousBackup() {
        world("Main"); val s = WorldStore(root); val bad = File(root, "bad.zip").apply { writeText("bad") }
        assertFails { s.restore(bad, "Main") }; assertEquals("MAIN", s.list().single().name); assertTrue(s.backups("Main").isEmpty())
    }
    @Test fun exportImportRoundTripAndCopy() {
        val a = File(root, "a"); val b = File(root, "b"); world("Main", a); val sa = WorldStore(a); val sb = WorldStore(b)
        val zip = sa.export("Main", File(root, "world.zip")); val imported = sb.import(zip, "Imported")
        assertEquals("MAIN", imported.name); sa.copy("Main", sb); assertEquals(setOf("Main", "Imported"), sb.list().map { it.id }.toSet()); assertFails { sa.copy("Main", sb) }
    }
    @Test fun mcworldIsRejectedWithTypedReason() {
        val f = File(root, "bedrock.mcworld").apply { writeText("bad") }
        assertEquals(WorldStore.Reason.BedrockWorld, assertFailsWith<WorldStore.Failure> { WorldStore(root).import(f) }.reason)
    }
    @Test fun trashUndoAndRunningGuard() {
        world("Main"); val s = WorldStore(root); val t = s.trash("Main"); assertTrue(s.list().isEmpty()); WorldStore(root).undo(t); assertEquals("Main", s.list().single().id)
        assertEquals(WorldStore.Reason.GameRunning, assertFailsWith<WorldStore.Failure> { WorldStore(root) { true }.trash("Main") }.reason)
    }
    @Test fun traversalAndSymlinksAreRejectedWithoutChangingSaves() {
        world("Keep"); val s = WorldStore(root)
        for (name in listOf("", ".", "..", "../saves", "Keep/region", "Keep\u0000", "x\\y")) { assertFails { s.trash(name) }; assertFails { s.backup(name) } }
        val outside = File(root, "outside").apply { mkdir() }; Files.createSymbolicLink(File(root, "saves/link").toPath(), outside.toPath())
        assertFails { s.trash("link") }; assertEquals("Keep", s.list().single().id)
    }
}
