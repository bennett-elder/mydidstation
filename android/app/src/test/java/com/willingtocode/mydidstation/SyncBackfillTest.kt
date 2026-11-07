package com.willingtocode.mydidstation

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The by-id backfill for a list that withholds a message's text.
 *
 * A real account listed a message whose body was a single emoji with no `message` field at
 * all, while the same message fetched by id had it. The list is what refresh uses, so
 * without this the text never arrives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncBackfillTest {

    private lateinit var server: MockWebServer

    private class FakeSettings(private val url: String) : ApiSettings {
        override fun credentials() = "user@example.com" to "secret"
        override fun endpoint() = url
    }

    /** The list row: present, with no text. */
    private fun listWithoutBody(id: Long): MockResponse = MockResponse().setBody(
        """{"status":"success","sms":[{"id":$id,"date":"2026-11-07 11:29:09","type":"1",""" +
            """"did":"5551234567","contact":"5551110002","message":"",""" +
            """"carrier_status":"Information not available"}]}"""
    )

    /** The same message, fetched by id, with the emoji intact. */
    private fun byId(id: Long): MockResponse = MockResponse().setBody(
        """{"status":"success","sms":[{"id":$id,"date":"2026-11-07 11:29:09","type":"1",""" +
            """"did":"5551234567","contact":"5551110002","message":"\ud83c\udf89",""" +
            """"carrier_status":"Information not available"}]}"""
    )

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun store(): Store {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("mydidstation.db")
        return Store(context)
    }

    @Test
    fun `a message whose text the list withheld is fetched by id`() = runBlocking {
        val id = 1_794_068_759_316L
        val asked = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                asked += if (body.contains("method=getSMS") || body.contains("name=\"method\"") &&
                    body.contains("getSMS")
                ) {
                    "getSMS"
                } else {
                    "getMMS"
                }
                return if (asked.last() == "getSMS") byId(id) else listWithoutBody(id)
            }
        }

        val store = store()
        store.putDid("5551234567")
        val api = VoipMs(FakeSettings(server.url("/rest.php").toString()))
        val sync = Sync(store, api, Notifier { _, _, _, _ -> })
        sync.refreshDid("5551234567")

        val row = store.messages(store.conversationId("5551234567", "5551110002")).single()
        assertEquals("the emoji should have been filled in", "\ud83c\udf89", row.body)
        assertTrue("the by-id call was never made: $asked", asked.contains("getSMS"))
    }

    /** The list row for a mixed text and emoji body: the text, without the emoji. */
    private fun listWithoutEmoji(id: Long): MockResponse = MockResponse().setBody(
        """{"status":"success","sms":[{"id":$id,"date":"2026-11-07 11:29:09","type":"1",""" +
            """"did":"5551234567","contact":"5551110002","message":"hello there",""" +
            """"carrier_status":"Information not available"}]}"""
    )

    /** The same message by id, emoji included. */
    private fun byIdMixed(id: Long): MockResponse = MockResponse().setBody(
        """{"status":"success","sms":[{"id":$id,"date":"2026-11-07 11:29:09","type":"1",""" +
            """"did":"5551234567","contact":"5551110002","message":"hello there \ud83c\udfc1",""" +
            """"carrier_status":"Information not available"}]}"""
    )

    @Test
    fun `a body the list stripped of its emoji is replaced by the by-id copy`() = runBlocking {
        // The case that is invisible without a check: the list body is not blank, it is just
        // wrong, so nothing about the row says the emoji is missing.
        val id = 1_794_070_003_554L
        server.enqueue(listWithoutEmoji(id))
        server.enqueue(byIdMixed(id))

        val store = store()
        store.putDid("5551234567")
        val api = VoipMs(FakeSettings(server.url("/rest.php").toString()))
        val sync = Sync(store, api, Notifier { _, _, _, _ -> })
        sync.refreshDid("5551234567")

        val row = store.messages(store.conversationId("5551234567", "5551110002")).single()
        assertEquals("hello there \ud83c\udfc1", row.body)
    }

    @Test
    fun `a verified body is not asked for again`() = runBlocking {
        val id = 1_794_070_003_555L
        server.enqueue(listWithoutEmoji(id))
        server.enqueue(byIdMixed(id))
        // The second pass sees the same row again, and must not ask for it again.
        server.enqueue(listWithoutEmoji(id))

        val store = store()
        store.putDid("5551234567")
        val api = VoipMs(FakeSettings(server.url("/rest.php").toString()))
        val sync = Sync(store, api, Notifier { _, _, _, _ -> })
        sync.refreshDid("5551234567")
        sync.refreshDid("5551234567")

        // Two list calls plus exactly one by-id call: the check is remembered per message.
        assertEquals("verification ran more than once", 3, server.requestCount)
    }

    @Test
    fun `an answer about a different message is never used`() = runBlocking {
        // The shape that put one message's text into another: the by-id call misses, the
        // fallback ignores its filter and returns the whole list, and the first row of that
        // list is a different message. Nothing may be taken from it.
        val wanted = 1_794_070_003_556L
        val other = 1_794_070_003_557L
        server.enqueue(
            MockResponse().setBody("""{"status":"no_sms"}""")
        )
        server.enqueue(
            MockResponse().setBody(
                """{"status":"success","sms":[
                     {"id":$other,"date":"2026-11-07 12:02:00","type":"1","did":"5551234567",
                      "contact":"5551110002","message":"Another album cover",
                      "carrier_status":"Information not available"}]}"""
            )
        )

        val store = store()
        store.putDid("5551234567")
        val api = VoipMs(FakeSettings(server.url("/rest.php").toString()))
        val answer = api.message(wanted)

        assertEquals("nothing should come back for a different id", null, answer)
        assertEquals("both lookups should have been tried", 2, server.requestCount)
    }

    @Test
    fun `the matching row is chosen out of a list`() = runBlocking {
        val wanted = 1_794_070_003_558L
        val other = 1_794_070_003_559L
        server.enqueue(
            MockResponse().setBody(
                """{"status":"success","sms":[
                     {"id":$other,"date":"2026-11-07 12:02:00","type":"1","did":"5551234567",
                      "contact":"5551110002","message":"Another album cover",
                      "carrier_status":"Information not available"},
                     {"id":$wanted,"date":"2026-11-07 12:03:00","type":"1","did":"5551234567",
                      "contact":"5551110002","message":"just the picture",
                      "carrier_status":"Information not available"}]}"""
            )
        )

        val api = VoipMs(FakeSettings(server.url("/rest.php").toString()))
        val answer = api.message(wanted)
        assertEquals("just the picture", answer?.body)
    }

    @Test
    fun `an outgoing message costs no verification`() = runBlocking {
        // Only incoming bodies can have been stripped by the list, and only they are fetched
        // again. A sent message's text is ours and already correct.
        val id = 1_794_068_759_317L
        server.enqueue(
            MockResponse().setBody(
                """{"status":"success","sms":[{"id":$id,"date":"2026-11-07 11:29:09",""" +
                    """"type":"0","did":"5551234567","contact":"5551110002",""" +
                    """"message":"plain text","carrier_status":"delivered"}]}"""
            )
        )

        val store = store()
        store.putDid("5551234567")
        val api = VoipMs(FakeSettings(server.url("/rest.php").toString()))
        val sync = Sync(store, api, Notifier { _, _, _, _ -> })
        sync.refreshDid("5551234567")

        assertEquals("a sent message should not be fetched by id", 1, server.requestCount)
        val row = store.messages(store.conversationId("5551234567", "5551110002")).single()
        assertEquals("plain text", row.body)
    }
}
