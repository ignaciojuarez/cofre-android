package com.ignacio.cofre.kit.updates

import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.HttpFailure
import com.ignacio.cofre.kit.downloads.DownloadFailure
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AppReleaseTest {
    private val apk = "https://github.com/ignaciojuarez/cofre-android/releases/download/v0.1.0-alpha.1/Cofre-v0.1.0-alpha.1.apk"
    private val sha = "a".repeat(64)

    private fun feed(apk: String = this.apk, sha256: String = sha, versionCode: Int = 2, minSdk: Int = 28) =
        """{"versionCode": $versionCode, "versionName": "0.1.0-alpha.1", "tag": "v0.1.0-alpha.1", "apk": "$apk", "size": 109340670,
            |"sha256": "$sha256", "minSdk": $minSdk, "notes": "Faster start.\nFewer crashes.", "extra": true}""".trimMargin()

    @Test fun parsesTheFeed() {
        val release = AppRelease.parse(feed())
        assertEquals(2, release.versionCode)
        assertEquals("0.1.0-alpha.1", release.versionName)
        assertEquals(109_340_670, release.size)
        assertEquals("Faster start.\nFewer crashes.", release.notes)
    }

    @Test fun newerOnlyWithAHigherVersionCode() {
        val release = AppRelease.parse(feed(versionCode = 2))
        assertTrue(release.isNewerThan(1))
        assertFalse(release.isNewerThan(2))
        assertFalse(release.isNewerThan(3))
    }

    @Test fun minSdkGatesTheDevice() {
        val release = AppRelease.parse(feed(minSdk = 31))
        assertFalse(release.fits(28))
        assertTrue(release.fits(31))
        assertTrue(release.fits(37))
    }

    @Test fun onlyGitHubReleaseDownloadsOverHttps() {
        assertTrue(AppRelease.trusted(apk))
        assertTrue(AppRelease.trusted("https://objects.githubusercontent.com/github-production-release-asset/1/abc?X-Amz=1"))
        listOf(
            "http://github.com/ignaciojuarez/cofre-android/releases/download/v1/Cofre.apk",
            "https://github.com/ignaciojuarez/cofre-android/archive/refs/heads/main.zip",
            "https://github.com/ignaciojuarez/cofre-android/raw/main/Cofre.apk",
            "https://github.com.evil.example/ignaciojuarez/cofre-android/releases/download/v1/Cofre.apk",
            "https://evil.example/ignaciojuarez/cofre-android/releases/download/v1/Cofre.apk",
            "https://user@github.com/ignaciojuarez/cofre-android/releases/download/v1/Cofre.apk",
            "https://github.com:8443/ignaciojuarez/cofre-android/releases/download/v1/Cofre.apk",
            "https://raw.githubusercontent.com/ignaciojuarez/cofre-android/main/Cofre.apk",
            "file:///sdcard/Cofre.apk",
            "not a url",
        ).forEach { assertFalse(AppRelease.trusted(it), it) }
        assertFailsWith<IllegalArgumentException> { AppRelease.parse(feed(apk = "https://evil.example/Cofre.apk")) }
    }

    @Test fun refusesABadChecksum() {
        listOf("A".repeat(64), "a".repeat(63), "g".repeat(64), "").forEach {
            assertFailsWith<IllegalArgumentException>(it) { AppRelease.parse(feed(sha256 = it)) }
        }
    }

    @Test fun missingFeedMeansNoRelease() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = 404))
            assertNull(AppRelease.fetch(server.url("/updates/latest.json").toString()))
        }
    }

    @Test fun fetchReadsTheFeedAndFailsOnServerErrors() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = feed()))
            server.enqueue(MockResponse(code = 500))
            val url = server.url("/updates/latest.json").toString()
            assertEquals(2, AppRelease.fetch(url)?.versionCode)
            assertEquals(500, assertFailsWith<HttpFailure> { AppRelease.fetch(url) }.status)
        }
    }

    @Test fun downloadChecksSizeAndHash(): Unit = runBlocking {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val dir = Files.createTempDirectory("cofre-update").toFile()
        MockWebServer().use { server ->
            server.start()
            val url = server.url("/Cofre.apk").toString()
            val good = AppRelease(2, "0.2", "v0.2", url, bytes.size.toLong(), Hashes.sha256(bytes), 28)
            repeat(3) { server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build()) }

            var seen = 0L
            val file = good.download(File(dir, "Cofre.apk")) { seen = it }
            assertContentEquals(bytes, file.readBytes())
            assertEquals(bytes.size.toLong(), seen)

            assertFailsWith<DownloadFailure.HashMismatch> { good.copy(sha256 = sha).download(File(dir, "bad-hash.apk")) }
            assertFailsWith<DownloadFailure.SizeMismatch> { good.copy(size = 10).download(File(dir, "bad-size.apk")) }
            assertEquals(listOf("Cofre.apk"), dir.list()!!.toList(), "failed downloads leave nothing behind")
        }
        dir.deleteRecursively()
    }
}
