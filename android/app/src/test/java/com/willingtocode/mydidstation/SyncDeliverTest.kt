package com.willingtocode.mydidstation

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Delivery, for a text and for a picture.
 *
 * Both start from a row that is already on screen, so the thing under test is what happens to
 * that row afterwards: confirmed by the id the API returned, removed when the API refused, and
 * removed when no id came back at all. The last case matters most and is the easiest to get
 * wrong, because the row was never sent and a poll can never match it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncDeliverTest {

    private lateinit var server: MockWebServer
    private lateinit var store: Store
    private lateinit var sync: Sync

    private val did = "5551234567"
    private val them = "5559990009"

    private class FakeSettings(private val url: String) : ApiSettings {
        override fun credentials() = "user@example.com" to "secret"
        override fun endpoint() = url
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("mydidstation.db")
        store = Store(context)
        // A conversation has a foreign key to the number, and the app only ever sends to a
        // number discovery has already stored. Without this the pending row is never created
        // and every assertion below would pass while testing nothing.
        store.putDid(did)
        val api = VoipMs(FakeSettings(server.url("/rest.php").toString()))
        sync = Sync(store, api, Notifier { _, _, _, _ -> })
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun reply(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    private fun pendingRow(body: String): Long {
        val id = store.insertPending(did, them, body, System.currentTimeMillis() / 1000)
        assertTrue("the pending row was not created at all", id > 0)
        assertEquals("the pending row is not in the thread", 1, rows().size)
        return id
    }

    private fun rows() = store.messages(store.ensureConversation(did, them))

    @Test
    fun `a picture is confirmed by the id the API returns`() = runBlocking {
        reply("""{"status":"success","mms":4242}""")
        reply("""{"status":"success","sms":[]}""")
        val pendingId = pendingRow("look at this")

        sync.deliverMms(did, them, "look at this", byteArrayOf(1, 2, 3), pendingId)

        val row = rows().single()
        assertFalse("the row is still pending after a confirmed send", row.pending)
        assertEquals("look at this", row.body)

        val sent = server.takeRequest().body.readUtf8()
        val refreshed = server.takeRequest().body.readUtf8()
        assertTrue("the send was not an MMS", sent.contains("sendMMS"))
        assertTrue("the thread was not refreshed after sending", refreshed.contains("getMMS"))
    }

    @Test
    fun `a refused picture is removed and the status comes back`() = runBlocking {
        reply("""{"status":"invalid_dst"}""")
        val pendingId = pendingRow("look at this")

        val failure = runCatching {
            sync.deliverMms(did, them, "look at this", byteArrayOf(1), pendingId)
        }.exceptionOrNull()

        assertTrue("expected an ApiException, got $failure", failure is ApiException)
        assertEquals("invalid_dst", (failure as ApiException).status)
        assertTrue("the picture that was never sent stayed in the thread", rows().isEmpty())
    }

    @Test
    fun `a picture with no id is dropped rather than left pending`() = runBlocking {
        // The API answered success but named nothing, so this row can never be matched to a
        // real message and would otherwise say "Sending" forever.
        reply("""{"status":"success"}""")
        reply("""{"status":"success","sms":[]}""")
        val pendingId = pendingRow("no id")

        sync.deliverMms(did, them, "no id", byteArrayOf(1), pendingId)

        assertTrue("a row with no id should not survive", rows().isEmpty())
    }

    @Test
    fun `a sent picture picks up the server's media on the next poll`() = runBlocking {
        // Found on the emulator, not by a test: the row exists as soon as it is sent, so the
        // upsert that the poll performs takes the conflict branch, and that branch used to
        // refresh only the carrier status. The picture therefore never acquired the server's
        // URL, and only looked right because the local file was still in memory.
        reply("""{"status":"success","mms":4243}""")
        reply("""{"status":"success","sms":[]}""")
        val pendingId = pendingRow("photo")
        sync.deliverMms(did, them, "photo", byteArrayOf(1, 2, 3), pendingId)

        assertTrue("the sent row should have no media yet", rows().single().media.isEmpty())

        // The next poll: the server now reports the same message with its attachment link.
        store.upsertRemote(
            did = did,
            contact = them,
            remoteId = 4243,
            incoming = false,
            body = "photo",
            media = listOf("http://localhost/media/sent-4243"),
            sentAt = System.currentTimeMillis() / 1000,
            unread = false,
            carrierStatus = "delivered",
        )

        val row = rows().single()
        assertEquals(
            "the server's attachment never reached the row",
            listOf("http://localhost/media/sent-4243"),
            row.media,
        )
        assertEquals("delivered", row.carrierStatus)
    }

    @Test
    fun `a stored attachment is not replaced by a later answer`() = runBlocking {
        // The fill is one-way on purpose: once the row has a URL, a repeat poll must not swap
        // it for a different one, or the cache key would churn on every refresh.
        store.putDid(did)
        store.upsertRemote(
            did = did,
            contact = them,
            remoteId = 4244,
            incoming = true,
            body = "",
            media = listOf("http://localhost/media/first.jpg"),
            sentAt = System.currentTimeMillis() / 1000,
            unread = false,
            carrierStatus = "",
        )
        store.upsertRemote(
            did = did,
            contact = them,
            remoteId = 4244,
            incoming = true,
            body = "",
            media = listOf("http://localhost/media/second.jpg"),
            sentAt = System.currentTimeMillis() / 1000,
            unread = false,
            carrierStatus = "",
        )
        assertEquals(listOf("http://localhost/media/first.jpg"), rows().single().media)
    }

    @Test
    fun `a failed refresh does not remove a picture that was sent`() = runBlocking {
        // The send succeeded and the row was confirmed, then the follow-up refresh blew up.
        // That must not delete the message: the server has it, and asking the person to send
        // again would cost them a second MMS.
        reply("""{"status":"success","mms":4245}""")
        server.enqueue(MockResponse().setResponseCode(500))
        val pendingId = pendingRow("sent and confirmed")

        sync.deliverMms(did, them, "sent and confirmed", byteArrayOf(1), pendingId)

        val row = rows().single()
        assertFalse("the confirmed row was left pending", row.pending)
        assertEquals("sent and confirmed", row.body)
    }

    @Test
    fun `a text still takes the same path and confirms`() = runBlocking {
        // The delivery body is shared now, so this is the guard that moving it did not change
        // what a text does.
        reply("""{"status":"success","sms":777}""")
        reply("""{"status":"success","sms":[]}""")
        val pendingId = pendingRow("hello")

        sync.deliver(did, them, "hello", pendingId)

        assertFalse(rows().single().pending)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue("the text went out as something else", sent.contains("sendSMS"))
    }
}
