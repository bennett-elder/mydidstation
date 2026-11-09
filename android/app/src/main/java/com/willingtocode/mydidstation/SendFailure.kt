package com.willingtocode.mydidstation

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/** What a failed send means for the person looking at it. */
internal enum class SendVerdict {
    /**
     * Provably nothing reached the server, so there is no message and no charge, and trying
     * again cannot produce a duplicate.
     */
    NOT_SENT,

    /**
     * The request may have been processed. Measured on a real account, 2026-11-09: a send
     * answered with a 503 and the picture arrived anyway. Trying again from here could send
     * the message a second time and pay for both, so the app asks before it does.
     */
    UNKNOWN,
}

/**
 * Reads a send failure and says whether the message might already exist.
 *
 * This is the difference between a retry that costs nothing and a retry that costs money, and
 * it cannot be answered from the error text alone. The rule is narrow on purpose: only the
 * failures that provably happened before anything was submitted count as not sent. Everything
 * else is treated as unknown, because being wrong in that direction shows a warning and being
 * wrong in the other direction sends the message twice.
 *
 * There is no idempotency key on `sendSMS` or `sendMMS` to fall back on, which is why this
 * classification exists at all rather than the app simply retrying like it does for a read.
 */
internal object SendFailure {

    fun verdict(error: Throwable): SendVerdict = when {
        // The API answered and refused. It processed the request and did not send anything.
        error is ApiException -> SendVerdict.NOT_SENT
        // A refusal the server decided before doing the work. A rate limit or a server fault
        // could still have been decided after it, so those stay unknown.
        error is HttpFailure -> if (error.code in 400..499 && error.code != 429) {
            SendVerdict.NOT_SENT
        } else {
            SendVerdict.UNKNOWN
        }
        // The name never resolved, so no connection was opened and nothing was written.
        error is UnknownHostException -> SendVerdict.NOT_SENT
        // Refused at the door, before the request went anywhere.
        error is ConnectException -> SendVerdict.NOT_SENT
        error is NoRouteToHostException -> SendVerdict.NOT_SENT
        // Raised before a request exists, by the app rather than the network.
        error is IllegalStateException -> SendVerdict.NOT_SENT
        error is IllegalArgumentException -> SendVerdict.NOT_SENT
        // Everything left here is ambiguous: a read timeout can arrive after the server has
        // taken the message, a TLS failure can happen mid body, and a plain IOException says
        // nothing about how far the request got.
        error is IOException -> SendVerdict.UNKNOWN
        else -> SendVerdict.UNKNOWN
    }
}
