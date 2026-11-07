package com.willingtocode.mydidstation

import android.media.MediaPlayer
import java.io.File
import java.io.IOException

/**
 * Where downloaded recordings wait between plays.
 *
 * The cache is the OS's to evict, so a file going missing is ordinary rather than an
 * error: [existing] reports whether the bytes are still there and the caller downloads
 * again when they are not. Nothing here touches the network.
 *
 * The folder is part of the file name because the API moves a message between INBOX and
 * Old when it is marked listened, so the same message number can describe two different
 * files and a stale copy under the old folder must not be played as the new one.
 */
class VoicemailCache(private val root: File) {

    private fun dir(): File = File(root, "voicemail").apply { mkdirs() }

    fun file(mailbox: String, folder: String, messageNum: Int): File =
        File(dir(), "$mailbox-${folder.ifBlank { "INBOX" }}-$messageNum.mp3")

    /** The file when it still holds bytes, null when it is missing or was evicted. */
    fun existing(mailbox: String, folder: String, messageNum: Int): File? =
        file(mailbox, folder, messageNum).takeIf { it.isFile && it.length() > 0 }

    /**
     * Writes the bytes and returns the file.
     *
     * A half-written file must never be playable, so the bytes land in a `.part` and are
     * renamed into place. Afterwards the oldest files are dropped until the cache is back
     * under [maxBytes], which is what keeps an always-open Voicemail tab from filling the
     * phone one recording at a time.
     */
    fun write(mailbox: String, folder: String, messageNum: Int, bytes: ByteArray): File {
        val target = file(mailbox, folder, messageNum)
        val temp = File(target.parentFile, target.name + ".part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) throw IOException("could not store ${target.name}")
        }
        trim(target)
        return target
    }

    fun files(): List<File> = dir().listFiles()?.filter { it.isFile } ?: emptyList()

    fun sizeBytes(): Long = files().sumOf { it.length() }

    /** How many recordings are on the device, for the storage row. */
    fun count(): Int = files().size

    fun clear() {
        files().forEach { it.delete() }
    }

    /** Drops the oldest files, never [keep], until the cache is under the cap. */
    private fun trim(keep: File) {
        var total = sizeBytes()
        if (total <= maxBytes) return
        val oldestFirst = files().filter { it != keep }.sortedBy { it.lastModified() }
        for (file in oldestFirst) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    companion object {
        /**
         * How much cached audio to hold. A ten minute recording is about 1.8 MB, so this
         * is dozens of messages on a phone where the cache is the smallest place to spend.
         */
        const val MAX_BYTES: Long = 64L * 1024 * 1024
    }

    private val maxBytes: Long = MAX_BYTES
}

/**
 * Plays one cached recording at a time.
 *
 * MediaPlayer needs a file rather than the API's base64, which is why playback goes
 * through [VoicemailCache]. Callers must call [stop] when they leave the screen.
 */
class VoicemailPlayer {

    private var player: MediaPlayer? = null

    /**
     * Starts [file], stopping whatever was playing.
     *
     * [onDone] runs when the recording ends or the player fails, so a screen can clear
     * its "playing" state without polling. A failure to start is thrown, not reported:
     * the caller has a screen to say so on.
     */
    fun play(file: File, onDone: () -> Unit) {
        stop()
        val created = MediaPlayer()
        try {
            created.setOnCompletionListener { onDone() }
            created.setOnErrorListener { _, _, _ ->
                stop()
                onDone()
                true
            }
            created.setDataSource(file.absolutePath)
            created.prepare()
            created.start()
        } catch (e: Exception) {
            runCatching { created.release() }
            throw e
        }
        player = created
    }

    fun pause() {
        player?.let { if (it.isPlaying) it.pause() }
    }

    fun resume() {
        player?.let { if (!it.isPlaying) it.start() }
    }

    fun isPaused(): Boolean = player?.let { !it.isPlaying } ?: false

    /**
     * Moves by [seconds] from where it is, clamped to the recording.
     *
     * Long recordings are the reason this exists: scrubbing a two minute message by
     * replaying it from the top is not a usable thing to ask of anyone.
     */
    fun seekBy(seconds: Int) {
        val current = player ?: return
        val duration = runCatching { current.duration }.getOrDefault(0)
        if (duration <= 0) return
        val target = (current.currentPosition + seconds * 1000).coerceIn(0, duration)
        current.seekTo(target)
    }

    fun durationMs(): Int = player?.let { runCatching { it.duration }.getOrDefault(0) } ?: 0

    fun positionMs(): Int = player?.let { runCatching { it.currentPosition }.getOrDefault(0) } ?: 0

    fun stop() {
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        player = null
    }
}
