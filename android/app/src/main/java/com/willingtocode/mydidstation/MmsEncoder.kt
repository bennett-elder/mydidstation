package com.willingtocode.mydidstation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Turns a picked picture into bytes small enough to send, following [MmsImage]'s plan.
 *
 * Short on purpose: none of it can be checked on the JVM, because Robolectric fakes the codec.
 * The parts worth testing are the plan, the scaling arithmetic and the orientation mapping, and
 * those live in [MmsImage] and [ImageHeader] with tests. What is left here is the walk.
 *
 * Two things this has to do that are easy to miss. Each attempt gets the edge it asked for, by
 * scaling after the power-of-two decode rather than trusting the decoder's rounding. And the
 * EXIF orientation is baked into the pixels, because the decode ignores it and the re-encode
 * drops the tag, so an upright photo would otherwise arrive rotated.
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
        val orientation = ImageHeader.orientation(original, original.size)

        var decoded: Bitmap? = null
        var decodedForSample = 0
        var prepared: Bitmap? = null
        var preparedForEdge = -1
        try {
            for (attempt in MmsImage.attempts(bounds.outWidth, bounds.outHeight)) {
                val sample = MmsImage.sampleSize(longest, attempt.longEdge)
                if (decoded == null || decodedForSample != sample) {
                    prepared?.recycle()
                    prepared = null
                    preparedForEdge = -1
                    decoded?.recycle()
                    decoded = decode(original, sample)
                    decodedForSample = sample
                    if (decoded == null) return null
                }
                // Every attempt gets its own edge. Reusing the previous bitmap when only the
                // edge changed made a smaller attempt compress the larger picture, which is a
                // plan that says one thing and bytes that do another.
                if (prepared == null || preparedForEdge != attempt.longEdge) {
                    prepared?.recycle()
                    prepared = prepare(decoded!!, attempt.longEdge, orientation)
                    preparedForEdge = attempt.longEdge
                    if (prepared == null) return null
                }
                val bytes = compress(prepared!!, attempt.quality)
                if (bytes != null && bytes.size <= target) return bytes
            }
        } finally {
            prepared?.recycle()
            decoded?.recycle()
        }
        return null
    }

    /** Scale to the planned edge, then turn the pixels the right way up. */
    private fun prepare(source: Bitmap, longEdge: Int, orientation: Int): Bitmap? {
        val (w, h) = MmsImage.scaledSize(source.width, source.height, longEdge)
        val scaled = if (w == source.width && h == source.height) {
            source
        } else {
            runCatching { Bitmap.createScaledBitmap(source, w, h, true) }.getOrNull() ?: return null
        }

        val transform = MmsImage.transform(orientation)
        val turned = if (!transform.needed) {
            if (scaled === source) source.copy(Bitmap.Config.ARGB_8888, false) else scaled
        } else {
            val matrix = Matrix()
            if (transform.flipX || transform.flipY) {
                matrix.postScale(if (transform.flipX) -1f else 1f, if (transform.flipY) -1f else 1f)
            }
            if (transform.degrees != 0) matrix.postRotate(transform.degrees.toFloat())
            val out = runCatching {
                Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
            }.getOrNull()
            if (scaled !== source) scaled.recycle()
            out ?: return null
        }
        // The scaled bitmap was a temporary; the prepared one is what the attempts compress.
        if (scaled !== source && turned !== scaled) scaled.recycle()
        return turned
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
