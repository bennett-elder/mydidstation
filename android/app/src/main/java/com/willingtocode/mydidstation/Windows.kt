package com.willingtocode.mydidstation

/** One request's bounds, in epoch seconds, both ends inclusive. */
data class Span(val from: Long, val to: Long)

/**
 * The window arithmetic, with no clock, no store and no HTTP in it.
 *
 * Every number here costs messages when it is wrong, and none of them fails loudly. A window
 * that starts too late loses whatever arrived in the gap and nothing reports it. One that
 * spans too much is the Cloudflare 522 this project has already met. The floor decides when
 * load-more stops being offered, so a wrong one either hides history or offers a tap that
 * fetches nothing.
 *
 * It lives here rather than inside [Sync] because the only way to check it there was to read
 * the mock's log: the arithmetic was tangled up with the requests it decides.
 */
internal object Windows {

    const val DAY_SECONDS = 24L * 60 * 60

    /** How far a poll reaches back before the last window ended, for late arrivals. */
    const val OVERLAP_SECONDS = DAY_SECONDS

    /** The first look at a number or mailbox, and the most one request may ask for. */
    const val INITIAL_WINDOW_SECONDS = 30L * DAY_SECONDS
    const val MAX_RANGE_SECONDS = INITIAL_WINDOW_SECONDS

    /** Where load-more stops: two years back. */
    const val LOOKBACK_FLOOR_SECONDS = 2L * 365 * DAY_SECONDS

    /** The first look: one window, ending now. */
    fun first(nowSec: Long): Span = Span(from = nowSec - INITIAL_WINDOW_SECONDS, to = nowSec)

    /**
     * Steady state: start just before the last window ended, so an arrival that landed
     * between two polls is fetched twice rather than missed. The cap is what stops a phone
     * that has been off for a month from asking for that month in one request.
     */
    fun steady(nowSec: Long, cursorSec: Long): Span =
        Span(from = maxOf(cursorSec - OVERLAP_SECONDS, nowSec - MAX_RANGE_SECONDS), to = nowSec)

    /**
     * The oldest instant a number is known to be filled back to.
     *
     * Zero means no record, which is a number stored by a version that did not keep one, and
     * the honest reading of that is the initial window rather than the beginning of time.
     */
    fun covered(recordedSec: Long, nowSec: Long): Long =
        if (recordedSec > 0) recordedSec else nowSec - INITIAL_WINDOW_SECONDS

    /** What the next load-more tap would fetch, or null when there is nothing left to pull. */
    fun lookBackSpan(nowSec: Long, coveredSec: Long): Span? {
        val floor = nowSec - LOOKBACK_FLOOR_SECONDS
        if (coveredSec <= floor) return null
        val from = maxOf(coveredSec - MAX_RANGE_SECONDS, floor)
        return Span(from = from, to = coveredSec - 1)
    }

    /** The label for that control, so the number on it matches what the tap will pull. */
    fun lookBackPlan(nowSec: Long, coveredSec: Long): LookBack {
        val span = lookBackSpan(nowSec, coveredSec) ?: return LookBack(days = 0, more = false)
        return LookBack(
            days = ((coveredSec - span.from) / DAY_SECONDS).toInt(),
            more = span.from > nowSec - LOOKBACK_FLOOR_SECONDS,
        )
    }

    /**
     * One window cut in two, for the case where the API reports more rows than one request
     * will return.
     *
     * The halves have to meet exactly: a gap loses rows, an overlap duplicates them, and
     * neither shows up anywhere except as messages that are quietly missing. `sameApiDay`
     * is what stops the recursion, because the dates on the wire have no time of day.
     */
    fun halves(from: Long, to: Long): Pair<Span, Span> {
        val mid = from + (to - from) / 2
        return Span(from = from, to = mid) to Span(from = mid + 1, to = to)
    }
}
