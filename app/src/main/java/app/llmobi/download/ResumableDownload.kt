package app.llmobi.download

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

class DownloadFailure(message: String, val retryable: Boolean = false) : IOException(message)

data class DownloadSpec(val url: String, val bytes: Long, val sha256: String = "", val gguf: Boolean = true)

/** Transfers only into a private partial file. The installed file changes after verification. */
class ResumableDownload(private val client: OkHttpClient) {
    companion object {
        const val STORAGE_RESERVE = 1024L * 1024 * 1024
        fun partFor(target: File) = File(target.path + ".part")
        fun metaFor(target: File) = File(target.path + ".part.properties")
        fun sha256(file: File, onChunk: () -> Unit = {}): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1024 * 1024)
                while (true) {
                    onChunk()
                    val n = input.read(buf)
                    if (n < 0) break
                    hash.update(buf, 0, n)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }

    suspend fun download(
        spec: DownloadSpec,
        target: File,
        freeBytes: () -> Long = { target.parentFile!!.usableSpace },
        onProgress: suspend (Long, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        require(spec.bytes > 0)
        target.parentFile!!.mkdirs()
        val part = partFor(target)
        val metaFile = metaFor(target)
        val identity = "${spec.url}\n${spec.bytes}\n${spec.sha256}"
        val meta = Properties().apply {
            runCatching { metaFile.inputStream().use { load(it) } }
        }
        fun reset() {
            if (part.exists() && !part.delete()) throw DownloadFailure("Could not restart the download. Free some storage and try again.")
            meta.clear()
            metaFile.delete()
        }
        fun saveMeta(etag: String?) {
            meta.setProperty("identity", identity)
            if (etag != null && !etag.startsWith("W/")) meta.setProperty("etag", etag)
            else meta.remove("etag")
            metaFile.outputStream().use { meta.store(it, null) }
        }
        if (meta.getProperty("identity") != identity || part.length() > spec.bytes) reset()

        // One restart is permitted if a server rejects a range or responds inconsistently.
        for (attempt in 0..1) {
            currentCoroutineContext().ensureActive()
            var have = part.length()
            if (have == spec.bytes) break
            if (freeBytes() < spec.bytes - have + STORAGE_RESERVE) {
                throw DownloadFailure("Not enough storage. Leave 1 GB free after the download, then try again.")
            }
            val request = Request.Builder().url(spec.url).header("Accept-Encoding", "identity").apply {
                if (have > 0) {
                    header("Range", "bytes=$have-")
                    meta.getProperty("etag")?.let { header("If-Range", it) }
                }
            }.build()
            val call = client.newCall(request)
            var restart = false
            coroutineScope {
                // Cancellation must also interrupt a blocked socket read, not just the next loop.
                val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        when {
                            response.code == 416 -> { reset(); restart = true; return@use }
                            response.code == 408 || response.code == 429 || response.code >= 500 ->
                                throw DownloadFailure("The download server is busy. Retrying shortly.", true)
                            response.code == 401 || response.code == 403 ->
                                throw DownloadFailure("This model is not publicly downloadable. Refresh the library or choose another model.")
                            response.code == 404 || response.code == 410 ->
                                throw DownloadFailure("This download is no longer available. Refresh the library and try again.")
                            response.code != 200 && response.code != 206 ->
                                throw DownloadFailure("The server rejected this download (HTTP ${response.code}).")
                        }
                        if (response.code == 206) {
                            val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                            val start = range?.groupValues?.get(1)?.toLongOrNull()
                            val end = range?.groupValues?.get(2)?.toLongOrNull()
                            val total = range?.groupValues?.get(3)?.toLongOrNull()
                            if (start != have || total != spec.bytes || end != spec.bytes - 1) {
                                reset(); restart = true; return@use
                            }
                            val oldEtag = meta.getProperty("etag")
                            val newEtag = response.header("ETag")
                            if (have > 0 && oldEtag != null && newEtag != null && oldEtag != newEtag) {
                                reset(); restart = true; return@use
                            }
                        } else if (have > 0) {
                            reset(); have = 0
                            if (freeBytes() < spec.bytes + STORAGE_RESERVE) throw DownloadFailure("Not enough storage to restart the download.")
                        }
                        val body = response.body ?: throw DownloadFailure("The server sent an empty response.", true)
                        if (body.contentLength() >= 0 && body.contentLength() != spec.bytes - have) {
                            reset()
                            throw DownloadFailure("The model file has changed. Refresh the library before downloading it again.")
                        }
                        saveMeta(response.header("ETag"))
                        onProgress(have, spec.bytes)
                        body.byteStream().use { input ->
                            RandomAccessFile(part, "rw").use { out ->
                                out.setLength(have)
                                out.seek(have)
                                val buf = ByteArray(256 * 1024)
                                var reportAt = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    if (have + n > spec.bytes) {
                                        // Invalidate while open; deleting an open file fails on Windows.
                                        out.setLength(0)
                                        metaFile.delete()
                                        throw DownloadFailure("The server sent an unexpected file. Refresh the library.")
                                    }
                                    if (freeBytes() < STORAGE_RESERVE + n) throw DownloadFailure("Storage filled up. Free some space and retry; your progress is saved.")
                                    out.write(buf, 0, n)
                                    have += n
                                    val now = System.nanoTime()
                                    if (now - reportAt >= 400_000_000L) {
                                        reportAt = now
                                        onProgress(have, spec.bytes)
                                    }
                                }
                                out.fd.sync()
                            }
                        }
                    }
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw failure
                } finally { cancellation.cancel() }
            }
            if (!restart) break
            if (attempt == 1) throw DownloadFailure("The server cannot resume this file correctly. Try again later.")
        }
        currentCoroutineContext().ensureActive()
        if (part.length() != spec.bytes) throw DownloadFailure("The connection ended before the file was complete. Your progress is saved.", true)
        val magicOk = !spec.gguf || part.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic.contentEquals(byteArrayOf(71, 71, 85, 70))
        }
        val context = currentCoroutineContext()
        if (!magicOk || (spec.sha256.isNotEmpty() && !sha256(part) { context.ensureActive() }.equals(spec.sha256, true))) {
            reset()
            throw DownloadFailure("The downloaded file failed verification. Please download it again.")
        }
        currentCoroutineContext().ensureActive()
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        metaFile.delete()
        onProgress(spec.bytes, spec.bytes)
        target
    }
}
