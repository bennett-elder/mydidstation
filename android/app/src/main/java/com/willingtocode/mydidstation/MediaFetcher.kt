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

/** What an attachment is, as far as drawing it goes. */
enum class MediaKind { Image, Audio, Video, Other }

/** The kinds of attachment this app recognises, from the bytes. */
enum class MediaType(val extension: String, val kind: MediaKind) {
    Png("png", MediaKind.Image),
    Jpeg("jpg", MediaKind.Image),
    Gif("gif", MediaKind.Image),
    Webp("webp", MediaKind.Image),

    // The MMS audio codecs: AMR is the standard one, and short clips are tens of kilobytes.
    Amr("amr", MediaKind.Audio),
    Mp3("mp3", MediaKind.Audio),
    /**
     * Deliberately not inline. OGG is rare in MMS, which also means less scrutiny of its
     * parser; another app can take that risk instead of this process.
     */
    Ogg("ogg", MediaKind.Other),
    /**
     * Deliberately not inline. MIDI is a score fed to a synthesiser, sonivox has the worst
     * vulnerability record of the audio parsers, and nobody sends MIDI in an MMS. Handing it
     * to another app costs the sender nothing and keeps that parser out of this process.
     */
    Midi("mid", MediaKind.Other),

    // H.263 in 3gp and MPEG-4 in mp4, which is what carriers transcode video into.
    ThreeGp("3gp", MediaKind.Video),
    Mp4("mp4", MediaKind.Video),
    /**
     * A 3GPP container with no video track: what a GrapheneOS voice note turns out to be.
     * Same container as [ThreeGp], so the two are told apart by their tracks, exactly as
     * [M4a] is told apart from [Mp4]. The extension stays `3gp`, because that is what it is.
     */
    ThreeGpAudio("3gp", MediaKind.Audio),
    /**
     * An MP4 container with no video track: what Google Messages records a voice note as.
     * The bytes are identical to [Mp4] until the tracks are inspected, so the two are told
     * apart after the file is written rather than from its header.
     */
    M4a("m4a", MediaKind.Audio),

    Pdf("pdf", MediaKind.Other),
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
            if (file.isFile && file.length() > 0) {
                // A container extension does not say whether it holds a voice note or a
                // video, and .3gp is used by both, so decide from the tracks again rather
                // than from the name the file happens to carry.
                val resolved = when (type) {
                    MediaType.ThreeGp, MediaType.ThreeGpAudio ->
                        if (audioOnlyMp4(file) == true) MediaType.ThreeGpAudio else MediaType.ThreeGp
                    MediaType.Mp4, MediaType.M4a ->
                        if (audioOnlyMp4(file) == true) MediaType.M4a else MediaType.Mp4
                    else -> type
                }
                return MediaResult.Loaded(file, resolved)
            }
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
        // A '+' in a query string means a space unless it is escaped, and these tokens are
        // base64, so a '+' is data. OkHttp preserves a literal '+' on the wire rather than
        // escaping it, which reaches the server as a space and comes back as an empty
        // text/html response with status 200 — the "empty attachment" that looked like the
        // vendor refusing to serve the file.
        val safeUrl = url.replace("+", "%2B")
        val request = Request.Builder().url(safeUrl).build()
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
                // An empty body with a 200 is the shape an endpoint returns when it wants a
                // session, a referer or a credential it did not get, and the status alone does
                // not say so. Naming the status, the type and the length is what turns this
                // from "empty" into something diagnosable from the phone.
                if (response.body.contentLength() == 0L) {
                    val kind = response.header("Content-Type") ?: "no type"
                    return MediaResult.Failed(
                        "empty attachment (HTTP ${response.code}, $kind, 0 bytes)",
                    )
                }
                response.body.byteStream().use { stream -> store(url, stream) }
            }
        } catch (e: IOException) {
            MediaResult.Failed("attachment failed (${e.message ?: "network"})")
        }
    }

    private fun store(url: String, stream: InputStream): MediaResult {
        val head = ByteArray(SNIFF_BYTES)
        val headLength = readUpTo(stream, head)
        if (headLength <= 0) return MediaResult.Failed("empty attachment (no bytes arrived)")
        val type = sniff(head, headLength) ?: return MediaResult.Failed("unrecognized attachment")

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
        // The container said "mp4"; whether that is a voice note or a video depends on its
        // tracks, which the header cannot say. Read the box structure ourselves rather than
        // asking a media parser, so nothing executes on these bytes before the user taps play.
        if (type == MediaType.Mp4 || type == MediaType.ThreeGp) {
            if (audioOnlyMp4(target) == true) {
                val spoken = if (type == MediaType.ThreeGp) {
                    MediaType.ThreeGpAudio
                } else {
                    MediaType.M4a
                }
                val voice = File(target.parentFile, "${key(url)}.${spoken.extension}")
                if (target.renameTo(voice)) return MediaResult.Loaded(voice, spoken)
            }
        }
        return MediaResult.Loaded(target, type)
    }

    /**
     * Whether an MP4 holds only audio, read from its box structure.
     *
     * True means audio-only, false means it carries video, null means the structure was not
     * understood and the caller should treat it as video. That default matters: handing the
     * file to another app is the safer answer when we are unsure.
     *
     * `moov` is often the last box rather than the first, because encoders write the media
     * data and patch the index in afterwards, so this follows the top-level boxes to wherever
     * `moov` lives instead of scanning the start of the file.
     */
    private fun audioOnlyMp4(file: File): Boolean? {
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return null
        }
        val handlers = mutableSetOf<String>()
        collectHandlers(bytes, 0, bytes.size, handlers, depth = 0)
        if (handlers.isEmpty()) return null
        return "soun" in handlers && "vide" !in handlers
    }

    /** Walks boxes collecting `hdlr` handler types, bounded so a crafted file cannot spin. */
    private fun collectHandlers(
        bytes: ByteArray,
        start: Int,
        end: Int,
        out: MutableSet<String>,
        depth: Int,
    ) {
        if (depth > 6 || end - start < 8) return
        var cursor = start
        var visited = 0
        while (cursor + 8 <= end && visited++ < 256) {
            val size = readInt32(bytes, cursor)
            val type = String(bytes, cursor + 4, 4, Charsets.US_ASCII)
            val headerSize = if (size == 1) 16 else 8
            val boxEnd = when {
                size == 0 -> end                       // "to the end of the file"
                size == 1 -> {                         // 64-bit size follows the type
                    if (cursor + 16 > end) return
                    val large = readInt64(bytes, cursor + 8)
                    if (large <= 0 || large > Int.MAX_VALUE) return
                    cursor + large.toInt()
                }
                size < 8 -> return                     // impossible for a real box
                else -> cursor + size
            }
            if (boxEnd <= cursor || boxEnd > end) return
            when (type) {
                "hdlr" ->
                    // version and flags, then a predefined field, then the handler type.
                    if (cursor + headerSize + 12 <= boxEnd) {
                        out += String(bytes, cursor + headerSize + 8, 4, Charsets.US_ASCII)
                    }
                "moov", "trak", "mdia", "minf", "stbl" ->
                    collectHandlers(bytes, cursor + headerSize, boxEnd, out, depth + 1)
            }
            cursor = boxEnd
        }
    }

    private fun readInt32(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private fun readInt64(bytes: ByteArray, at: Int): Long =
        (readInt32(bytes, at).toLong() shl 32) or (readInt32(bytes, at + 4).toLong() and 0xFFFFFFFFL)

    private fun tooLarge(): MediaResult.Failed =
        MediaResult.Failed("attachment too large (over ${MAX_BYTES / (1024 * 1024)} MB)")

    /** Keeps the cache under the cap by dropping the oldest files, never the newest. */
    fun sizeBytes(): Long = dir().listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** How many attachments are on the device, for the storage row. */
    fun count(): Int = dir().listFiles()?.count { it.isFile } ?: 0

    /** Removes every cached attachment. A file being drawn is the caller's concern. */
    fun clear() {
        dir().listFiles()?.forEach { it.delete() }
    }

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

            // "#!AMR" starts every AMR file, which is the codec MMS voice notes use.
            matches(0, 0x23, 0x21, 0x41, 0x4D, 0x52) -> MediaType.Amr
            // Either an ID3 tag or a bare MPEG frame header.
            matches(0, 0x49, 0x44, 0x33) -> MediaType.Mp3
            at(0) == 0xFF && (at(1) and 0xE0) == 0xE0 -> MediaType.Mp3
            matches(0, 0x4F, 0x67, 0x67, 0x53) -> MediaType.Ogg
            matches(0, 0x4D, 0x54, 0x68, 0x64) -> MediaType.Midi

            // mp4 and 3gp both put "ftyp" at byte 4. Distinguishing audio-only mp4 from video
            // would mean parsing the boxes, and the two are treated the same way anyway.
            matches(4, 0x66, 0x74, 0x79, 0x70) ->
                if (matches(8, 0x33, 0x67, 0x70) || matches(8, 0x33, 0x67, 0x65)) {
                    MediaType.ThreeGp
                } else {
                    MediaType.Mp4
                }

            matches(0, 0x25, 0x50, 0x44, 0x46, 0x2D) -> MediaType.Pdf
            else -> null
        }
    }

    companion object {
        /** How many bytes are needed to identify the four formats: WebP needs twelve. */
        private const val SNIFF_BYTES = 16

        /**
         * One attachment's ceiling.
         *
         * The vendor's own limit is 1.2 MB per file (`voipms-api.md`, the sendMMS section), so
         * anything past that is not a real MMS leaving a carrier intact — it is a provider
         * anomaly or a payload aimed at the phone. 2 MB accepts everything legitimate with
         * headroom and refuses the rest. Note this caps the *file*, not the decoded bitmap: a
         * small file can still decode huge, which is why the decoder is given a target size
         * rather than trusted.
         */
        const val MAX_BYTES = 2L * 1024 * 1024

        /** What all the cached attachments may occupy together. */
        const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    }
}
