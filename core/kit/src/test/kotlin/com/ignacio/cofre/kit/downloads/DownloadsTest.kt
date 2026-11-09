package com.ignacio.cofre.kit.downloads

import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Kit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import mockwebserver3.*
import okio.BufferedSink
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DownloadsTest {
    @TempDir lateinit var root: File
    private fun queue(concurrency: Int = 2, attempts: Int = 3): DownloadQueue = DownloadQueue(File(root, "queue.json"), concurrency = concurrency, attempts = attempts, backoffMillis = 1)
    private fun item(server: MockWebServer, text: String = "abcdef", name: String = "a.jar"): DownloadItem = DownloadItem(server.url("/$name").toString(), File(root, name).path, size = text.length.toLong(), sha1 = Hashes.sha1(text.toByteArray()), sha512 = Hashes.sha512(text.toByteArray()))
    @Test fun resumesAfterConnectionCut() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val text = "0123456789".repeat(5000)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val offset = request.headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
                    return if (offset == null) MockResponse.Builder().body(text).onResponseBody(SocketEffect.CloseSocket()).build()
                    else MockResponse.Builder().code(206).body(text.substring(offset)).addHeader("Content-Range", "bytes $offset-${text.lastIndex}/${text.length}").build()
                }
            }
            val queue = queue(); val item = item(server, text); queue.enqueue(item)
            assertEquals(DownloadQueue.Result.Completed, withTimeout(10_000) { queue.run() })
            assertEquals(text, File(item.destination).readText()); assertFalse(item.part.exists())
            assertNull(server.takeRequest().headers["Range"])
            val range = server.takeRequest().headers["Range"]!!
            assertTrue(range.startsWith("bytes=")); assertTrue(range != "bytes=0-")
            assertEquals(1, queue.progress.value.files); assertEquals(text.length.toLong(), queue.progress.value.bytes)
        }
    }
    @Test fun serverIgnoringRangeRestartsWithoutAppending() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse(body = "abcdef"))
            val queue = queue(); val item = item(server); item.part.writeText("abc"); queue.enqueue(item)
            assertEquals(DownloadQueue.Result.Completed, queue.run()); assertEquals("abcdef", File(item.destination).readText())
            assertEquals("bytes=3-", server.takeRequest().headers["Range"])
        }
    }
    @Test fun hashMismatchPreservesExistingDestination() = runBlocking {
        MockWebServer().use { server ->
            server.start(); repeat(2) { server.enqueue(MockResponse(body = "xxxxxx")) }
            val queue = queue(attempts = 2); val item = item(server); File(item.destination).writeText("original")
            queue.enqueue(item); assertIs<DownloadQueue.Result.Failed>(queue.run())
            assertEquals("original", File(item.destination).readText()); assertFalse(item.part.exists()); assertEquals(2, server.requestCount)
        }
    }
    @Test fun wrongSizeAndInvalidRangeNeverPublishFiles() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse.Builder().code(206).body("def").addHeader("Content-Range", "bytes 1-3/6").build())
            val queue = queue(attempts = 1); val item = item(server); item.part.writeText("abc"); queue.enqueue(item)
            assertIs<DownloadQueue.Result.Failed>(queue.run()); assertFalse(File(item.destination).exists())
            server.enqueue(MockResponse(body = "abc")); queue.retry(item.id)
            assertIs<DownloadQueue.Result.Failed>(queue.run()); assertFalse(File(item.destination).exists())
        }
    }
    @Test fun cancellationStopsIoAndKeepsResumeData() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse.Builder().body("abcdef").throttleBody(1, 1, TimeUnit.SECONDS).build())
            val queue = queue(); val item = item(server); queue.enqueue(item)
            val run = launch { queue.run() }
            withTimeout(5000) { queue.progress.first { it.bytes > 0 } }
            withTimeout(2000) { run.cancelAndJoin() }
            assertEquals(DownloadItem.State.Paused, queue.progress.value.items.single().state)
            assertTrue(item.part.length() > 0); assertFalse(File(item.destination).exists())
            assertEquals(DownloadItem.State.Paused, queue().progress.value.items.single().state)
        }
    }
    @Test fun cancellingAnItemDoesNotCancelItsSiblings() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse.Builder().body("abcdef").throttleBody(1, 1, TimeUnit.SECONDS).build())
            val queue = queue(); val item = item(server); queue.enqueue(item)
            val run = launch { queue.run() }
            withTimeout(5000) { queue.progress.first { it.bytes > 0 } }; queue.cancel(item.id)
            withTimeout(2000) { run.join() }
            assertEquals(DownloadItem.State.Cancelled, queue.progress.value.items.single().state)
        }
    }
    @Test fun boundedParallelDownloads() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val active = AtomicInteger(); val peak = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().body(object : MockResponseBody {
                    override val contentLength: Long = 6
                    override fun writeTo(sink: BufferedSink) {
                        val now = active.incrementAndGet(); peak.updateAndGet { maxOf(it, now) }
                        try { Thread.sleep(80); sink.writeUtf8("abcdef"); sink.flush() } finally { active.decrementAndGet() }
                    }
                }).build()
            }
            val queue = queue(concurrency = 2); repeat(6) { queue.enqueue(item(server, name = "$it.jar")) }
            assertEquals(DownloadQueue.Result.Completed, queue.run())
            assertEquals(2, peak.get()); assertEquals(6, queue.progress.value.files)
        }
    }
    @Test fun thousandsOfSmallFilesQueueAndFinishQuickly() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse(body = request.url.encodedPath) }
            // Like a game's assets: 4,000 files in 256 folders.
            val items = (0 until 4_000).map { i ->
                val body = "/asset-$i"
                DownloadItem(server.url(body).toString(), File(root, "objects/${"%02x".format(i % 256)}/$i").path, size = body.length.toLong(), sha1 = Hashes.sha1(body.toByteArray()))
            }
            val queue = DownloadQueue(File(root, "queue.json"), concurrency = 8)
            val started = System.nanoTime()
            assertEquals(4_000, queue.enqueue(items).size)
            assertTrue(System.nanoTime() - started < 2_000_000_000L, "enqueue took ${(System.nanoTime() - started) / 1_000_000} ms")
            assertEquals(DownloadQueue.Result.Completed, withTimeout(30_000) { queue.run() })
            assertEquals(4_000, queue.progress.value.files)
            assertEquals("/asset-3999", File(items.last().destination).readText())
            // The journal ends with the final state, so a new queue sees everything finished.
            assertTrue(DownloadQueue(File(root, "queue.json")).progress.value.items.all { it.state == DownloadItem.State.Finished })
        }
    }
    @Test fun batchRejectsTheSameDestinationTwice() {
        val a = DownloadItem("https://cdn/a", File(root, "a").path)
        assertFailsWith<IllegalArgumentException> { queue().enqueue(listOf(a, a.copy(id = "other"))) }
    }
    @Test fun processDeathRestoresRunningAsPausedAndUsesPartLength() {
        val item = DownloadItem("https://cdn/a", File(root, "a").path, state = DownloadItem.State.Running, completedBytes = 500)
        item.part.writeText("abc"); File(root, "queue.json").writeText(Kit.json.encodeToString(listOf(item)))
        val restored = queue().progress.value.items.single()
        assertEquals(DownloadItem.State.Paused, restored.state); assertEquals(3, restored.completedBytes)
    }
    @Test fun meteredThresholdAndApproval() = runBlocking {
        assertFalse(DownloadQueue.asksOnMetered(DownloadQueue.METERED_ASK_BYTES, true))
        assertTrue(DownloadQueue.asksOnMetered(DownloadQueue.METERED_ASK_BYTES + 1, true))
        assertFalse(DownloadQueue.asksOnMetered(Long.MAX_VALUE, false)); assertFalse(DownloadQueue.asksOnMetered(Long.MAX_VALUE, true, true))
        MockWebServer().use { server ->
            server.start(); val queue = queue(); val item = item(server).copy(size = DownloadQueue.METERED_ASK_BYTES + 1)
            queue.enqueue(item, metered = true)
            assertIs<DownloadQueue.Result.NeedsMeteredApproval>(queue.run(metered = true)); assertEquals(0, server.requestCount)
            server.enqueue(MockResponse(body = "abcdef")); queue.enqueue(item.copy(size = 6, allowMetered = true))
            assertEquals(DownloadQueue.Result.Completed, queue.run(metered = true))
        }
    }
    @Test fun diskPreflightIncludesMarginAndCannotOverflow() {
        assertFalse(DiskPreflight(100, DiskPreflight.MARGIN + 99).hasRoom)
        assertTrue(DiskPreflight(100, DiskPreflight.MARGIN + 100).hasRoom)
        assertTrue(DiskPreflight(0, 0).hasRoom)
        assertEquals(Long.MAX_VALUE, DiskPreflight(Long.MAX_VALUE, 1).needBytes)
        assertFailsWith<DownloadFailure.NoDiskSpace> { queue().enqueue(DownloadItem("https://cdn/a", File(root, "a").path, size = Long.MAX_VALUE)) }
    }
    @Test fun summaryWeightsActiveBytesAndHidesFinished() {
        fun item(title: String, state: DownloadItem.State, size: Long = 100, done: Long = 0) = DownloadItem("https://cdn/a", title, title, size, state = state, completedBytes = done)
        val s = DownloadSummary(listOf(item("b", DownloadItem.State.Running, done = 50), item("a", DownloadItem.State.Queued, size = 300), item("c", DownloadItem.State.Finished), item("d", DownloadItem.State.Cancelled)))
        assertEquals(listOf("a", "b"), s.visible.map { it.title }); assertEquals(0.125, s.fraction); assertEquals("Downloading 2 · 12%", s.title)
        assertEquals("A download stopped", DownloadSummary(listOf(item("a", DownloadItem.State.Failed))).title)
        assertEquals("A download waits for Wi-Fi", DownloadSummary(listOf(item("a", DownloadItem.State.WaitingForWifi))).title)
    }
}
