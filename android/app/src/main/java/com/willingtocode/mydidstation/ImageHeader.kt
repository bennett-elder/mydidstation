package com.willingtocode.mydidstation

/**
 * The dimensions a picture claims for itself, read from its header without decoding it.
 *
 * This exists because the download cap bounds the file and nothing bounded the bitmap: a two
 * megabyte PNG is perfectly legal and can declare a pixel count no phone should try to
 * allocate, and the oldest trick there is. The header says how big the picture claims to be,
 * so the claim can be refused before anything decodes it.
 *
 * Only dimensions are read here. No pixel data is touched, nothing is allocated to the
 * declared size, and a header that does not parse returns null rather than a guess: the caller
 * treats "unknown" as "let the decoder decide", which is the behaviour that shipped before.
 */
internal object ImageHeader {

    /** Declared pixels for the formats the app draws, or null when the header does not say. */
    fun pixels(type: MediaType, bytes: ByteArray, length: Int): Long? = when (type) {
        MediaType.Png -> pngPixels(bytes, length)
        MediaType.Jpeg -> jpegPixels(bytes, length)
        else -> null
    }

    /** PNG puts width and height in the IHDR chunk, which has to be first: bytes 16 through 23. */
    private fun pngPixels(bytes: ByteArray, length: Int): Long? {
        if (length < 24) return null
        val isIhdr = at(bytes, length, 12) == 'I'.code &&
            at(bytes, length, 13) == 'H'.code &&
            at(bytes, length, 14) == 'D'.code &&
            at(bytes, length, 15) == 'R'.code
        if (!isIhdr) return null
        val width = be32(bytes, length, 16) ?: return null
        val height = be32(bytes, length, 20) ?: return null
        if (width <= 0 || height <= 0) return null
        return width.toLong() * height.toLong()
    }

    /**
     * JPEG has no fixed offset: the frame header sits wherever the encoder put it, so this
     * walks the segment markers until it finds one that carries the size.
     *
     * Returns null at the start of scan, because by then no frame header was found and the
     * picture is not going to describe itself in the part that was read.
     */
    private fun jpegPixels(bytes: ByteArray, length: Int): Long? {
        var i = 2
        while (i + 3 < length) {
            if (at(bytes, length, i) != 0xFF) {
                i++
                continue
            }
            var marker = at(bytes, length, i + 1)
            while (marker == 0xFF && i + 2 < length) {
                i++
                marker = at(bytes, length, i + 1)
            }
            // Standalone markers carry no length to skip.
            if (marker == 0x01 || marker in 0xD0..0xD8) {
                i += 2
                continue
            }
            if (i + 4 >= length) return null
            val size = (at(bytes, length, i + 2) shl 8) or at(bytes, length, i + 3)
            if (size < 2) return null

            // SOF0 through SOF15 carry the frame size. C4 is a table, C8 and CC are reserved.
            val carriesSize = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (carriesSize) {
                if (i + 9 >= length) return null
                val height = (at(bytes, length, i + 5) shl 8) or at(bytes, length, i + 6)
                val width = (at(bytes, length, i + 7) shl 8) or at(bytes, length, i + 8)
                if (width <= 0 || height <= 0) return null
                return width.toLong() * height.toLong()
            }
            if (marker == 0xDA) return null
            i += 2 + size
        }
        return null
    }

    private fun at(bytes: ByteArray, length: Int, index: Int): Int =
        if (index < length) bytes[index].toInt() and 0xFF else -1

    private fun be32(bytes: ByteArray, length: Int, from: Int): Long? {
        if (from + 3 >= length) return null
        val value = (at(bytes, length, from).toLong() shl 24) or
            (at(bytes, length, from + 1).toLong() shl 16) or
            (at(bytes, length, from + 2).toLong() shl 8) or
            at(bytes, length, from + 3).toLong()
        // A width above Int.MAX_VALUE is a made-up header, not a picture.
        return if (value > Int.MAX_VALUE.toLong()) null else value
    }
}
