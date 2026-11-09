package com.willingtocode.mydidstation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Which send failures are safe to try again without asking.
 *
 * The stakes are asymmetric on purpose. Calling an ambiguous failure "not sent" sends a
 * message twice and pays for both. Calling a certain failure "unknown" shows one extra
 * warning. So the tests that matter most are the ones pinning that the ambiguous cases stay
 * ambiguous, and they are written to fail if anything is promoted to [SendVerdict.NOT_SENT].
 */
class SendFailureTest {

    @Test
    fun `a name that did not resolve never left the phone`() {
        assertEquals(SendVerdict.NOT_SENT, SendFailure.verdict(UnknownHostException("no dns")))
    }

    @Test
    fun `a refused connection never left the phone`() {
        assertEquals(SendVerdict.NOT_SENT, SendFailure.verdict(ConnectException("refused")))
        assertEquals(SendVerdict.NOT_SENT, SendFailure.verdict(NoRouteToHostException("no route")))
    }

    @Test
    fun `the API refusing a message means it was not sent`() {
        assertEquals(SendVerdict.NOT_SENT, SendFailure.verdict(ApiException("invalid_dst")))
        assertEquals(SendVerdict.NOT_SENT, SendFailure.verdict(ApiException("invalid_credentials")))
    }

    @Test
    fun `a server refusal decided before the work is not sent`() {
        assertEquals(SendVerdict.NOT_SENT, SendFailure.verdict(HttpFailure(400, "sendMMS", null)))
        assertEquals(SendVerdict.NOT_SENT, SendFailure.verdict(HttpFailure(401, "sendMMS", null)))
        assertEquals(SendVerdict.NOT_SENT, SendFailure.verdict(HttpFailure(403, "sendMMS", null)))
        assertEquals(SendVerdict.NOT_SENT, SendFailure.verdict(HttpFailure(413, "sendMMS", null)))
    }

    @Test
    fun `a 503 may have been processed and must not be retried behind the user`() {
        // Measured: a send answered with 503 and the picture arrived. This is the case the
        // whole classification exists for.
        assertEquals(SendVerdict.UNKNOWN, SendFailure.verdict(HttpFailure(503, "sendMMS", null)))
        assertEquals(SendVerdict.UNKNOWN, SendFailure.verdict(HttpFailure(500, "sendMMS", null)))
        assertEquals(SendVerdict.UNKNOWN, SendFailure.verdict(HttpFailure(502, "sendMMS", null)))
        assertEquals(SendVerdict.UNKNOWN, SendFailure.verdict(HttpFailure(504, "sendMMS", null)))
        assertEquals(SendVerdict.UNKNOWN, SendFailure.verdict(HttpFailure(429, "sendMMS", null)))
    }

    @Test
    fun `a timeout may have arrived after the server took it`() {
        assertEquals(SendVerdict.UNKNOWN, SendFailure.verdict(SocketTimeoutException("read")))
        assertEquals(SendVerdict.UNKNOWN, SendFailure.verdict(InterruptedIOException("read")))
        assertEquals(SendVerdict.UNKNOWN, SendFailure.verdict(IOException("connection reset")))
    }

    @Test
    fun `a failure from before any request is not sent`() {
        assertEquals(SendVerdict.NOT_SENT, SendFailure.verdict(IllegalStateException("Not configured")))
    }

    @Test
    fun `an unrecognized failure is treated as maybe sent`() {
        // The default has to be the cautious one: a new exception type nobody classified is
        // not evidence that the message was not sent.
        assertEquals(SendVerdict.UNKNOWN, SendFailure.verdict(RuntimeException("something new")))
    }
}
