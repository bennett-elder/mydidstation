package com.willingtocode.mydidstation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Turns a picked picture into bytes small enough to send, following [MmsImage]'s plan.
 *
 * Deliberately short, because none of it can be checked on the JVM: Robolectric fakes the
 * codec, so a decode returns whatever bitmap it is told to rather than the picture's. The part
 * worth testing is the plan, and it lives in [MmsImage] with tests. What this adds is the walk:
 * decode once per long edge, compress at each quality, keep the first that fits.
 */
internal object MmsEncoder {

    /**
     * A picked file larger than this is refused rather than read into memory.
     *
     * A camera photo is a few megabytes, so this only excludes video and the pathological. The
     * refusal is deliberate: reading a hundred megabytes to throw most of it away is how a
     * phone runs out of memory on the main thread's behalf.
     */
    const val MAX_SOURCE_BYTES = 48L * 1024 * 1024

    /** Encoded JPEG bytes at or under [target], or null when the picture cannot get there. */
    fun encode(source: InputStream, target: Int = MmsImage.TARGET_BYTES): ByteArray? {
        val original = readBounded(source) ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var decoded: Bitmap? = null
        var decodedForSample = 0
        try {
            for (attempt in MmsImage.attempts(bounds.outWidth, bounds.outHeight)) {
                val sample = MmsImage.sampleSize(longest, attempt.longEdge)
                if (decoded == null || decodedForSample != sample) {
                    decoded?.recycle()
                    decoded = decode(original, sample)
                    decodedForSample = sample
                    if (decoded == null) return null
                }
                val bytes = compress(decoded, attempt.quality)
                if (bytes != null && bytes.size <= target) return bytes
            }
        } finally {
            decoded?.recycle()
        }
        return null
    }

    /** The whole file, or null when it is larger than [MAX_SOURCE_BYTES] or unreadable. */
    private fun readBounded(source: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        return try {
            while (true) {
                val read = source.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_SOURCE_BYTES) return null
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        } finally {
            source.close()
        }
    }

    private fun decode(original: ByteArray, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(original, 0, original.size, options) }
            .getOrNull()
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val ok = runCatching {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }.getOrDefault(false)
        return if (ok) out.toByteArray() else null
    }
}
