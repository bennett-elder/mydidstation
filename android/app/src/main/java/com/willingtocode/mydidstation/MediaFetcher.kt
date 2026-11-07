package com.willingtocode.mydidstation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** The kinds of attachment this app will draw. Anything else is not an image. */
enum class MediaType(val extension: String) {
    Png("png"),
    Jpeg("jpg"),
    Gif("gif"),
    Webp("webp"),
}

/** What fetching one attachment produced. */
sealed interface MediaResult {
    /** The bytes are on disk, capped and identified. */
    data class Loaded(val file: File, val type: MediaType) : MediaResult

    /** No file was kept. [reason] is meant to be shown to a person. */
    data class Failed(val reason: String) : MediaResult
}

/**
 * Downloads an attachment, or refuses to.
 *
 * Everything here exists because the sender is anyone who has one of your numbers. The rules
 * are BACKLOG.md section 3 item 5's, enforced here rather than in a rendering library so
 * there is one place to read them:
 *
 * - The size is capped while streaming, not after, and `Content-Length` is checked but not
 *   trusted, because a response may declare one thing and send another.
 * - The type is decided from the first bytes, never from the URL or `Content-Type`. A URL
 *   ending `.jpg` can serve anything, and the header is the sender's word.
 * - Anything that is not one of four image formats is refused rather than handed to a decoder
 *   to make up its own mind.
 *
 * The cache is the OS's to empty, so a missing file is a re-download rather than an error.
 */
class MediaFetcher(
    private val client: OkHttpClient,
    private val root: File,
) {

    private fun dir(): File = File(root, "mms").apply { mkdirs() }

    /** One lock per URL, so duplicate attachments on screen share a single download. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun key(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun cached(url: String): MediaResult.Loaded? {
        val key = key(url)
        for (type in MediaType.entries) {
            val file = File(dir(), "$key.${type.extension}")
            if (file.isFile && file.length() > 0) return MediaResult.Loaded(file, type)
        }
        return null
    }

    suspend fun fetch(url: String): MediaResult = withContext(Dispatchers.IO) {
        cached(url)?.let { return@withContext it }
        // One download at a time per URL. Two bubbles can show the same attachment, and
        // without this they race on the same file and one of them loses the rename.
        val mutex = locks.computeIfAbsent(key(url)) { Mutex() }
        mutex.withLock {
            cached(url) ?: download(url)
        }
    }

    private fun download(url: String): MediaResult {
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = if (response.code == 404 || response.code == 410) {
                        "attachment expired"
                    } else {
                        "attachment failed (HTTP ${response.code})"
                    }
                    return MediaResult.Failed(reason)
                }
                if (response.body.contentLength() > MAX_BYTES) return tooLarge()
                response.body.byteStream().use { stream -> store(url, stream) }
            }
        } catch (e: IOException) {
            MediaResult.Failed("attachment failed (${e.message ?: "network"})")
        }
    }

    private fun store(url: String, stream: InputStream): MediaResult {
        val head = ByteArray(SNIFF_BYTES)
        val headLength = readUpTo(stream, head)
        if (headLength <= 0) return MediaResult.Failed("attachment was empty")
        val type = sniff(head, headLength) ?: return MediaResult.Failed("not an image")

        val target = File(dir(), "${key(url)}.${type.extension}")
        // Unique per attempt: two writers of the same URL must not share a temp file, even
        // though the lock above should already have prevented it.
        val temp = File(target.parentFile, "${target.name}.${System.nanoTime()}.part")
        var total = headLength.toLong()
        try {
            temp.outputStream().use { out ->
                out.write(head, 0, headLength)
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    // The cap is enforced here, mid-stream, so a declared length of -1 or a
                    // lie about it cannot fill the phone.
                    if (total > MAX_BYTES) {
                        out.flush()
                        return tooLarge()
                    }
                    out.write(buffer, 0, read)
                }
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return MediaResult.Failed("attachment could not be stored")
                }
            }
        } catch (e: IOException) {
            temp.delete()
            return MediaResult.Failed("attachment failed (${e.message ?: "write"})")
        } finally {
            if (temp.exists() && temp.length() > MAX_BYTES) temp.delete()
        }
        trim(keep = target)
        return MediaResult.Loaded(target, type)
    }

    private fun tooLarge(): MediaResult.Failed =
        MediaResult.Failed("attachment too large (over ${MAX_BYTES / (1024 * 1024)} MB)")

    /** Keeps the cache under the cap by dropping the oldest files, never the newest. */
    private fun trim(keep: File) {
        val files = dir().listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in files.filter { it != keep }.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun readUpTo(stream: InputStream, buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val read = stream.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }

    /** The type from the bytes themselves, or null when they are not an image we draw. */
    internal fun sniff(bytes: ByteArray, length: Int): MediaType? {
        fun at(index: Int) = if (index < length) bytes[index].toInt() and 0xFF else -1
        fun matches(offset: Int, vararg expected: Int) =
            expected.indices.all { at(offset + it) == expected[it] }

        return when {
            matches(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> MediaType.Png
            matches(0, 0xFF, 0xD8, 0xFF) -> MediaType.Jpeg
            matches(0, 0x47, 0x49, 0x46, 0x38) -> MediaType.Gif
            matches(0, 0x52, 0x49, 0x46, 0x46) && matches(8, 0x57, 0x45, 0x42, 0x50) ->
                MediaType.Webp
            else -> null
        }
    }

    companion object {
        /** How many bytes are needed to identify the four formats: WebP needs twelve. */
        private const val SNIFF_BYTES = 16

        /** One attachment's ceiling. MMS is normally far smaller than this. */
        const val MAX_BYTES = 8L * 1024 * 1024

        /** What all the cached attachments may occupy together. */
        const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    }
}
