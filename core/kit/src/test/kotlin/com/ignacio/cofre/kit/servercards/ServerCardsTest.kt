package com.ignacio.cofre.kit.servercards

import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.modpacks.PackFile
import com.ignacio.cofre.kit.servercards.ServerJoinError.Reason
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64
import kotlin.test.*

class ServerCardsTest {
    @TempDir lateinit var root: File
    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/servercards/$name.json")).use { it.readBytes() }
    private fun card(name: String = "fabric-modrinth"): ServerCard = CardDecoder.decodeCard(fixture(name))
    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun publicKey(seed: ByteArray): ByteArray = ByteArray(32).also { Ed25519.generatePublicKey(seed, 0, it, 0) }
    private fun sign(seed: ByteArray, bytes: ByteArray): ByteArray = ByteArray(64).also { Ed25519.sign(seed, 0, bytes, 0, bytes.size, it, 0) }
    private fun signed(card: ServerCard = card(), seed: ByteArray = ByteArray(32) { 1 }, oldSeed: ByteArray? = null): VerifiedCard {
        val payload = Kit.json.encodeToString(card).toByteArray(); val key = publicKey(seed)
        val e = CardEnvelope("cofre-card-envelope/1", b64(payload), "key-${seed[0]}", b64(key), b64(sign(seed, payload)), oldSeed?.let { b64(sign(it, key)) })
        return CardDecoder.decodeEnvelope(Kit.json.encodeToString(e).toByteArray())
    }
    @Test fun everyValidIOSExampleDecodes() {
        for (name in listOf("fabric-modrinth", "forge-curseforge-restricted", "ios-override", "update-diff")) assertTrue(card(name).id.isNotBlank())
    }
    @Test fun everyMaliciousIOSExampleFails() {
        for (name in listOf("http-malicious", "path-malicious", "wildcard-malicious", "missing-required-invalid")) assertFailsWith<ServerJoinError>(name) { card(name) }
    }
    @Test fun signedIOSGoldenFixturesVerifyExactPayloadBytes() {
        for (name in listOf("fabric-modrinth", "forge-curseforge-restricted", "ios-override", "update-diff")) {
            val v = CardDecoder.decodeEnvelope(fixture("signed/$name.envelope")); assertContentEquals(fixture("signed/$name"), v.payload); assertEquals(card(name).id, v.card.id)
        }
    }
    @Test fun maliciousSignedGoldenFixturesStillFailValidation() {
        for (name in listOf("http-malicious", "path-malicious", "wildcard-malicious", "missing-required-invalid")) assertFailsWith<ServerJoinError> { CardDecoder.decodeEnvelope(fixture("signed/$name.envelope")) }
    }
    @Test fun payloadWhitespaceIsCoveredBySignature() {
        val v = signed(); CardDecoder.verify(v.envelope, v.payload)
        assertEquals(Reason.SignatureInvalid, assertFailsWith<ServerJoinError> { CardDecoder.verify(v.envelope, v.payload + byteArrayOf(32)) }.reason)
    }
    @Test fun keyAndSignatureTamperingAreRejected() {
        val v = signed(); val e = v.envelope.copy(publicKey = b64(publicKey(ByteArray(32) { 2 })))
        assertEquals(Reason.SignatureInvalid, assertFailsWith<ServerJoinError> { CardDecoder.decodeEnvelope(Kit.json.encodeToString(e).toByteArray()) }.reason)
    }
    @Test fun malformedEnvelopeBase64FormatAndSizeFail() {
        assertFailsWith<ServerJoinError> { CardDecoder.decodeEnvelope("{}".toByteArray()) }
        val e = signed().envelope
        for (bad in listOf(e.copy(format = "other"), e.copy(payload = e.payload + "="), e.copy(publicKey = "x"), e.copy(signature = "!".repeat(86)))) assertFailsWith<ServerJoinError> { CardDecoder.decodeEnvelope(Kit.json.encodeToString(bad).toByteArray()) }
        assertEquals(Reason.SizeLimit, assertFailsWith<ServerJoinError> { CardDecoder.decodeEnvelope(ByteArray(513 * 1024)) }.reason)
    }
    @Test fun unknownTopLevelCardFieldIsRejected() { assertFailsWith<ServerJoinError> { CardDecoder.decodeCard(fixture("fabric-modrinth").decodeToString().replaceFirst("{", "{\"unknown\":1,").toByteArray()) } }
    @Test fun HTTPSUserinfoAndUnlistedPortsAreRejected() {
        for (url in listOf("http://owner.test/x", "https://user:pass@owner.test/x", "https://owner.test:444/x")) assertFailsWith<ServerJoinError> { URLPolicy.validate(url, listOf("owner.test")) }
        assertEquals(444, URLPolicy.validate("https://owner.test:444/x", listOf("owner.test:444")).port)
    }
    @Test fun IDNAndTrailingDotCanonicalizeWithoutSuffixAllowlisting() {
        assertEquals("xn--bcher-kva.example", URLPolicy.validate("https://bücher.example./x", listOf("xn--bcher-kva.example")).host)
        assertFailsWith<ServerJoinError> { URLPolicy.validate("https://evilowner.test/x", listOf("owner.test")) }
        assertFailsWith<ServerJoinError> { URLPolicy.validate("https://sub.owner.test/x", listOf("*.owner.test")) }
    }
    @Test fun redirectsEnforceHTTPSExactHostsAndThreeHopLimit() {
        assertEquals("https://owner.test/next", URLPolicy.redirect("https://owner.test/x", "/next", listOf("owner.test"), 1).toString())
        assertFailsWith<ServerJoinError> { URLPolicy.redirect("https://owner.test/x", "https://evil.test/x", listOf("owner.test"), 1) }
        assertFailsWith<ServerJoinError> { URLPolicy.redirect("https://owner.test/x", "http://owner.test/x", listOf("owner.test"), 1) }
        assertFailsWith<ServerJoinError> { URLPolicy.redirect("https://owner.test/x", "/x", listOf("owner.test"), 4) }
    }
    @Test fun deepLinkParsesCardAndAdvertisedServerBinding() {
        val link = "cofre://join?server=play.example.test&card=https%3A%2F%2Fexample.test%2Fx"
        assertEquals("https://example.test/x", Discovery.deepLink(link, card()).toString())
        assertFailsWith<ServerJoinError> { Discovery.deepLink(link.replace("play.example.test", "other.test"), card()) }
        assertFailsWith<ServerJoinError> { Discovery.deepLink("cofre://join?card=http%3A%2F%2Fa.test") }
        assertFailsWith<ServerJoinError> { Discovery.deepLink("cofre://join?card=https%3A%2F%2Fa.test&card=https%3A%2F%2Fb.test") }
    }
    @Test fun wellKnownIsThePortableDiscoveryPath() { assertEquals("https://a.test/.well-known/minecraft-server-card.json", Discovery.wellKnown("a.test").toString()); assertFails { Discovery.wellKnown("a.test/evil") } }
    @Test fun deepLinkPinAcceptsPublicKeyOrSHA256AndRejectsChanges() {
        val v = signed(); Discovery.verifyPin(v, v.envelope.publicKey); Discovery.verifyPin(v, Hashes.sha256(base64url(v.envelope.publicKey)))
        assertEquals(Reason.KeyChanged, assertFailsWith<ServerJoinError> { Discovery.verifyPin(v, "wrong") }.reason)
    }
    @Test fun tofuFirstVisitSameKeyAndDiskReload() {
        val v = signed(); val store = FileTrustStore(File(root, "trust.json")); assertTrue(TrustVerifier.evaluate(v, store).firstVisit)
        assertFalse(store.file.exists()); TrustVerifier.confirm(v, store)
        val reloaded = FileTrustStore(store.file); val next = TrustVerifier.evaluate(v, reloaded); assertFalse(next.firstVisit); assertFalse(next.keyChanged)
    }
    @Test fun unapprovedKeyChangeCannotOverwritePin() {
        val store = FileTrustStore(File(root, "trust.json")); val first = signed(); TrustVerifier.confirm(first, store)
        val changed = signed(seed = ByteArray(32) { 2 }); assertTrue(TrustVerifier.evaluate(changed, store).keyChanged)
        assertEquals(Reason.KeyChanged, assertFailsWith<ServerJoinError> { TrustVerifier.confirm(changed, store) }.reason)
        assertEquals(first.envelope.publicKey, store.record(first.card.security.origin)?.fingerprint)
    }
    @Test fun keyRotationRequiresOldKeysSignatureOverRawNewKey() {
        val oldSeed = ByteArray(32) { 1 }; val store = FileTrustStore(File(root, "trust.json")); TrustVerifier.confirm(signed(seed = oldSeed), store)
        val rotated = signed(seed = ByteArray(32) { 2 }, oldSeed = oldSeed); assertFalse(TrustVerifier.evaluate(rotated, store).keyChanged); TrustVerifier.confirm(rotated, store)
        assertEquals(rotated.envelope.publicKey, store.record(rotated.card.security.origin)?.fingerprint)
        val invalid = signed(seed = ByteArray(32) { 3 }, oldSeed = oldSeed); assertTrue(TrustVerifier.evaluate(invalid, store).keyChanged)
    }
    @Test fun trustIsBoundToOriginAndCorruptionFailsClosed() {
        val v = signed(); val store = FileTrustStore(File(root, "trust.json")); store.save(TrustRecord("https://other.test", "key", v.envelope.publicKey))
        assertTrue(TrustVerifier.evaluate(v, store).firstVisit); store.file.writeText("broken"); assertFails { TrustVerifier.evaluate(v, store) }
    }
    @Test fun androidOverridesCoexistWithIOSAndAreAppliedByDefault() {
        val c = card("ios-override").copy(platformOverrides = PlatformOverrides(ios = PlatformOverride(exclude = listOf("mods/ios.jar")), android = PlatformOverride(exclude = listOf("mods/android.jar"), replace = listOf(Replacement("mod:renderer", "mod:vulkan")))))
        val i = MrpackIndex(emptyMap(), listOf(PackFile("mods/ios.jar", fileSize = 2), PackFile("mods/android.jar", fileSize = 3)))
        val plan = InstallPlanner.plan(c, i); assertEquals(listOf("mods/ios.jar"), plan.entries.map { it.path }); assertEquals(2, plan.totalDownloadSize); assertEquals("mod:vulkan", plan.replacements.single().with)
        assertEquals(listOf("mods/android.jar"), InstallPlanner.plan(c, i, platform = "ios").entries.map { it.path })
        val decoded = CardDecoder.decodeCard(Kit.json.encodeToString(c).toByteArray()); assertNotNull(decoded.platformOverrides?.android)
    }
    @Test fun IOSGoldenOverrideStillExcludesPortableModIdentifiers() {
        val i = MrpackIndex(emptyMap(), listOf(PackFile("mod:voicechat", fileSize = 2)))
        assertTrue(InstallPlanner.plan(card("ios-override"), i, platform = "ios").entries.isEmpty())
    }
    @Test fun overrideTraversalIsRejectedOnBothPlatforms() {
        for (path in listOf("../bad", "/bad", "x\\bad", "C:/bad")) {
            val c = card().copy(platformOverrides = PlatformOverrides(android = PlatformOverride(exclude = listOf(path))))
            assertEquals(Reason.PathTraversal, assertFailsWith<ServerJoinError> { CardDecoder.decodeCard(Kit.json.encodeToString(c).toByteArray()) }.reason)
        }
    }
    @Test fun plannerCalculatesTotalsEnvironmentAndManualCurseforge() {
        val c = card().let { it.copy(security = it.security.copy(allowedHosts = it.security.allowedHosts + "curseforge.com")) }
        val i = MrpackIndex(emptyMap(), listOf(PackFile("mods/a.jar", downloads = listOf("https://curseforge.com/a"), fileSize = 4), PackFile("mods/server.jar", fileSize = 2, env = mapOf("client" to "unsupported"))))
        val plan = InstallPlanner.plan(c, i); assertEquals(4, plan.totalDownloadSize); assertTrue(plan.entries.single().manual)
    }
    @Test fun plannerRejectsEscapingPathsUnlistedHostsAndOversizeFiles() {
        for (file in listOf(PackFile("../bad"), PackFile("mods/a.jar", downloads = listOf("https://evil.test/a")), PackFile("mods/a.jar", fileSize = 251L shl 20))) assertFailsWith<ServerJoinError> { InstallPlanner.plan(card(), MrpackIndex(emptyMap(), listOf(file))) }
    }
    @Test fun updateDiffKeepsUserOptionalAndCountsChangedDownloads() {
        val old = MrpackIndex(emptyMap(), listOf(PackFile("a", mapOf("sha512" to "old"), fileSize = 1), PackFile("optional"), PackFile("removed")))
        val next = MrpackIndex(emptyMap(), listOf(PackFile("a", mapOf("sha512" to "new"), fileSize = 4), PackFile("b", fileSize = 3)))
        val d = UpdateDiffer.diff(old, next, setOf("optional")); assertEquals(listOf("b"), d.added); assertEquals(listOf("a"), d.updated); assertEquals(listOf("removed"), d.removed); assertEquals(7, d.downloadSize); assertEquals(listOf("optional"), d.preservedUserOptional)
    }
    @Test fun installVerifyChecksHashSizeAndUnknownSizeCap() {
        val file = File(root, "pack").apply { writeText("pack bytes") }; val c = card().copy(pack = card().pack.copy(sha512 = Hashes.sha512(file), sizeBytes = file.length()))
        InstallEngine.verify(file, c); InstallEngine.verify(file, c.copy(pack = c.pack.copy(sizeBytes = null)))
        assertFailsWith<ServerJoinError> { InstallEngine.verify(file, c.copy(pack = c.pack.copy(sha512 = "0".repeat(128)))) }
        assertFailsWith<ServerJoinError> { InstallEngine.verify(file, c.copy(pack = c.pack.copy(sizeBytes = 1))) }
    }
    @Test fun contentHashCacheValidatesKeysAndStoredBytes() {
        val file = File(root, "source").apply { writeText("content") }; val hash = Hashes.sha512(file); val cache = FileHashCache(File(root, "cache"))
        assertNull(cache.get(hash)); val cached = cache.put(file, hash); assertEquals("content", cached.readText()); cached.writeText("corrupt"); assertFailsWith<ServerJoinError> { cache.get(hash) }; assertFails { cache.get("../escape") }
    }
    @Test fun goldenLoaderAddressHashAndCompatibilityFieldsRemainPortable() {
        val c = card(); assertEquals(Loader.Fabric, c.loader.type); assertEquals(25565, c.server.addresses.first().port); assertEquals(128, c.pack.sha512.length); assertNotNull(card("ios-override").platformOverrides?.ios)
    }
}
