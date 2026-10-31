package app.llmobi.download

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

class ResumableDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var target: File
    private lateinit var download: ResumableDownload
    private val data = "GGUF0123456789abcdefghijklmnopqrstuvwxyz".toByteArray()
    private fun spec(hash: String = "") = DownloadSpec(server.url("/model").toString(), data.size.toLong(), hash)
    private fun response(bytes: ByteArray = data) = MockResponse().setBody(Buffer().write(bytes)).setHeader("ETag", "\"v1\"")
    @Before fun setup() {
        server = MockWebServer(); server.start()
        target = File(temp.root, "model.gguf")
        download = ResumableDownload(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build())
    }
    @After fun teardown() { server.shutdown() }
    private fun partial(bytes: ByteArray, identity: DownloadSpec = spec(), etag: String = "\"v1\"") {
        ResumableDownload.partFor(target).writeBytes(bytes)
        Properties().apply {
            setProperty("identity", "${identity.url}\n${identity.bytes}\n${identity.sha256}")
            setProperty("etag", etag)
        }.also { p -> ResumableDownload.metaFor(target).outputStream().use { p.store(it, null) } }
    }
    private suspend fun failure(block: suspend () -> Unit): DownloadFailure {
        try { block() } catch (e: DownloadFailure) { return e }
        throw AssertionError("Expected download to fail")
    }
    @Test fun completeFileIsVerifiedAndPromoted() = runBlocking {
        server.enqueue(response())
        val progress = mutableListOf<Long>()
        download.download(spec(), target) { have, _ -> progress.add(have) }
        assertArrayEquals(data, target.readBytes())
        assertEquals(data.size.toLong(), progress.last())
        assertFalse(ResumableDownload.partFor(target).exists())
    }
    @Test fun resumedDownloadRequestsExactRange() = runBlocking {
        partial(data.copyOfRange(0, 10))
        server.enqueue(response(data.copyOfRange(10, data.size)).setResponseCode(206)
            .setHeader("Content-Range", "bytes 10-${data.size - 1}/${data.size}"))
        download.download(spec(), target)
        val req = server.takeRequest()
        assertEquals("bytes=10-", req.getHeader("Range"))
        assertEquals("\"v1\"", req.getHeader("If-Range"))
        assertArrayEquals(data, target.readBytes())
    }
    @Test fun ignoredRangeRestartsWithoutAppending() = runBlocking {
        partial(data.copyOfRange(0, 10)); server.enqueue(response())
        download.download(spec(), target)
        assertArrayEquals(data, target.readBytes())
    }
    @Test fun changedCatalogDiscardsOldPartial() = runBlocking {
        partial("old corrupted file".toByteArray(), spec().copy(url = "https://old.example/model"))
        server.enqueue(response()); download.download(spec(), target)
        assertNull(server.takeRequest().getHeader("Range"))
        assertArrayEquals(data, target.readBytes())
    }
    @Test fun wrongContentRangeNeverAppends() = runBlocking {
        partial(data.copyOfRange(0, 10))
        server.enqueue(response(data.copyOfRange(10, data.size)).setResponseCode(206).setHeader("Content-Range", "bytes 11-${data.size - 1}/${data.size}"))
        server.enqueue(response())
        download.download(spec(), target)
        assertArrayEquals(data, target.readBytes())
        server.takeRequest(); assertNull(server.takeRequest().getHeader("Range"))
    }
    @Test fun rejectedRangeRestartsAndNeverWritesErrorBody() = runBlocking {
        partial(data.copyOfRange(0, 10)); server.enqueue(MockResponse().setResponseCode(416).setBody("ERROR"))
        server.enqueue(response()); download.download(spec(), target)
        assertArrayEquals(data, target.readBytes())
    }
    @Test fun completedPartialNeedsNoNetwork() = runBlocking {
        partial(data); download.download(spec(), target)
        assertEquals(0, server.requestCount); assertArrayEquals(data, target.readBytes())
    }
    @Test fun permanentHttpErrorLeavesInstalledFileUntouched() = runBlocking {
        target.writeText("existing model"); server.enqueue(MockResponse().setResponseCode(404))
        val error = failure { download.download(spec(), target) }
        assertFalse(error.retryable); assertEquals("existing model", target.readText())
    }
    @Test fun serverBusyIsRetryable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(failure { download.download(spec(), target) }.retryable)
        assertFalse(target.exists())
    }
    @Test fun truncatedChunkedBodyIsNotInstalled() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(data.copyOfRange(0, 12)), 4))
        assertTrue(failure { download.download(spec(), target) }.retryable)
        assertFalse(target.exists()); assertEquals(12, ResumableDownload.partFor(target).length())
    }
    @Test fun incorrectLengthIsRejected() = runBlocking {
        server.enqueue(response(data.copyOfRange(0, 12)))
        assertFalse(failure { download.download(spec(), target) }.retryable)
        assertFalse(target.exists())
    }
    @Test fun oversizedChunkedResponseIsDiscardedAndCanBeRetried() = runBlocking {
        target.writeText("old installed file")
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(data + data), 7))
        failure { download.download(spec(), target) }
        assertEquals("old installed file", target.readText())
        assertEquals(0L, ResumableDownload.partFor(target).length())
        server.enqueue(response())
        download.download(spec(), target)
        assertArrayEquals(data, target.readBytes())
    }
    @Test fun corruptHashDiscardsPartialAndPreservesInstalledFile() = runBlocking {
        target.writeText("old"); server.enqueue(response())
        failure { download.download(spec("a".repeat(64)), target) }
        assertEquals("old", target.readText()); assertFalse(ResumableDownload.partFor(target).exists())
    }
    @Test fun htmlErrorWithSuccessfulStatusIsNotInstalled() = runBlocking {
        server.enqueue(response(ByteArray(data.size) { 'x'.code.toByte() }))
        failure { download.download(spec(), target) }; assertFalse(target.exists())
    }
    @Test fun insufficientStorageDoesNotStartNetwork() = runBlocking {
        failure { download.download(spec(), target, freeBytes = { 0L }) }
        assertEquals(0, server.requestCount)
    }
    @Test fun cancellationInterruptsBlockedNetworkRead() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.IO) { download.download(spec(), target) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        withTimeout(1500) { job.cancelAndJoin() }
        assertFalse(target.exists())
    }
}
