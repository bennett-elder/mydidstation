package com.willingtocode.mydidstation

/**
 * How long an MMS caption may be.
 *
 * Measured, not read: the vendor documents 2048, and a caption of that length was stored by
 * VoIP.ms and delivered to a handset as 2000 characters, cut mid-sentence with nothing in the
 * response saying so. Because the app's own copy keeps all 2048, the damage is invisible from
 * inside the app, which is exactly why the limit is taken from the far end.
 */
internal object MmsCaption {

    const val LIMIT = 2000

    /**
     * Whether this caption arrives whole.
     *
     * Counts UTF-16 units, which for ASCII is characters and is what the measurement covered.
     * What the carrier counts for an emoji is not measured, so this errs on the strict side: a
     * caption may be refused that would have arrived, which is better than one that arrives
     * cut in half.
     */
    fun fits(caption: String): Boolean = caption.length <= LIMIT
}
