package com.willingtocode.mydidstation

/**
 * What a destination is, once the shapes are stripped off it.
 *
 * These two functions decide which conversation an arriving message lands in and whether the
 * composer will send at all, so they are the same rule seen from two sides. They lived in the
 * API client, which meant the UI reached into the HTTP layer to ask a question about numbers.
 *
 * The rule here is the live one and may change. The v11 migration carries a frozen copy of what
 * it did on the day it shipped, in `Migrations`, and must not be pointed at this.
 */
object PhoneNumbers {

    /** The shortest short code in common use, which is also the shortest reply worth sending. */
    private const val SHORTCODE_MIN_DIGITS = 5

    /** A US or Canadian common short code is five or six digits. */
    private const val SHORTCODE_MAX_DIGITS = 6

    /** A NANP number without its country code. */
    private const val NANP_DIGITS = 10

    /** E.164 caps a subscriber number at fifteen digits, so nothing longer is addressable. */
    private const val MAX_DESTINATION_DIGITS = 15

    /**
     * A contact with no country code in front of it.
     *
     * The API reports a five or six digit sender as if it were a NANP number, so the short code
     * `89866` arrives as `189866`. Left alone, that is stored, shown, and replied to as a
     * destination that does not exist, and the carrier answers `malformed-invalid-to-number`.
     *
     * The `1` can only be the country code: US and Canadian common short codes are allocated in
     * the ranges 20000-99999 and 200000-999999, so a real one never begins with 1. The
     * `in '2'..'9'` test is that range written down, and it also refuses to touch a code from
     * somewhere that does use a leading 1. Anything that fails both branches is returned as it
     * is, which turns an unknown shape into a send that fails loudly rather than one that
     * quietly goes to the wrong number.
     */
    fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (!digits.startsWith("1")) return digits
        val rest = digits.drop(1)
        return when {
            rest.length == NANP_DIGITS -> rest
            rest.length in SHORTCODE_MIN_DIGITS..SHORTCODE_MAX_DIGITS &&
                rest.first() in '2'..'9' -> rest
            else -> digits
        }
    }

    /**
     * Whether a destination is worth sending to.
     *
     * Seven digits was the old floor here: right for a phone number, wrong for the one kind of
     * sender people most need to answer. Marketing and alert traffic arrives from a five or six
     * digit shortcode, and `STOP` is the reply that stops it, so refusing to compose to a
     * shortcode quietly kept people subscribed. The floor is now a shortcode's length and the
     * ceiling is E.164's fifteen digits, which together still refuse an empty field and a
     * pasted wall of numbers.
     */
    fun isSendable(raw: String): Boolean {
        val digits = normalizePhone(raw)
        return digits.length >= SHORTCODE_MIN_DIGITS && digits.length <= MAX_DESTINATION_DIGITS
    }
}
