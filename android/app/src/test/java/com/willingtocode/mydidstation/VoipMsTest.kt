package com.willingtocode.mydidstation

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The API layer against recorded response bodies.
 *
 * Robolectric is here for `org.json`, which is Android's implementation rather than
 * the JVM's, and it is the one the app ships against. The bodies follow the
 * vendor's documented shapes, including fields this app ignores, so a rename shows
 * up as a failure instead of as silence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VoipMsTest {

    private lateinit var server: MockWebServer

    private class FakeSettings(private val url: String) : ApiSettings {
        override fun credentials() = "user@example.com" to "secret"
        override fun endpoint() = url
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun api() = VoipMs(FakeSettings(server.url("/rest.php").toString()))

    private fun reply(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    @Test
    fun `discovery reports which numbers can be messaged on`() = runBlocking {
        reply(
            """
            {"status":"success","dids":[
              {"did":"5551234567","sms_available":"1","sms_enabled":"1"},
              {"did":"5559876543","sms_available":"1","sms_enabled":"0"}
            ]}
            """.trimIndent()
        )
        val found = api().discoverDids()
        assertEquals(2, found.size)
        assertTrue(found[0].smsEnabled)
        assertFalse(found[1].smsEnabled)
    }

    @Test
    fun `a number with no sms_enabled field is treated as enabled`() = runBlocking {
        reply("""{"status":"success","dids":[{"did":"5551234567","sms_available":"1"}]}""")
        val found = api().discoverDids()
        assertEquals(1, found.size)
        assertTrue("absent must not hide a number", found[0].smsEnabled)
    }

    @Test
    fun `a leading country code is dropped from numbers`() = runBlocking {
        reply("""{"status":"success","dids":[{"did":"15551234567","sms_enabled":"1"}]}""")
        assertEquals("5551234567", api().discoverDids().single().did)
    }

    @Test
    fun `messages parse direction, body, contact and carrier status`() = runBlocking {
        reply(
            """
            {"status":"success","sms":[
              {"id":"9001","date":"2026-11-06 16:59:51","type":"1","did":"5551234567",
               "contact":"15551110001","message":"first one",
               "carrier_status":"Information not available",
               "col_media1":"","col_media2":"","col_media3":""},
              {"id":"9002","date":"2026-11-06 17:02:00","type":"0","did":"5551234567",
               "contact":"5551110001","message":"a reply",
               "carrier_status":"delivered",
               "col_media1":"","col_media2":"","col_media3":""}
            ]}
            """.trimIndent()
        )
        val messages = api().fetchMessages("5551234567", 0, 1_800_000_000, 100)
        assertEquals(2, messages.size)
        assertTrue(messages[0].incoming)
        assertEquals("first one", messages[0].body)
        assertEquals("5551110001", messages[0].contact)
        assertFalse(messages[1].incoming)
        assertEquals("delivered", messages[1].carrierStatus)
    }

    @Test
    fun `media columns become a list and empty ones are dropped`() = runBlocking {
        reply(
            """
            {"status":"success","sms":[
              {"id":"9001","date":"2026-11-06 16:59:51","type":"1","did":"5551234567",
               "contact":"5551110001","message":"see attached","carrier_status":"",
               "col_media1":"https://voip.ms/media.php?map=AAA","col_media2":"",
               "col_media3":"https://voip.ms/media.php?map=BBB"}
            ]}
            """.trimIndent()
        )
        val message = api().fetchMessages("5551234567", 0, 1_800_000_000, 100).single()
        assertEquals(
            listOf("https://voip.ms/media.php?map=AAA", "https://voip.ms/media.php?map=BBB"),
            message.media,
        )
    }

    @Test
    fun `the timestamp is read at the fixed offset the request asked for`() = runBlocking {
        // A summer date on purpose: in July, New York is UTC-4 under daylight
        // saving while the request asks for a flat -5. Winter dates cannot tell
        // the two apart, which is how this went unnoticed.
        reply(
            """
            {"status":"success","sms":[
              {"id":"9001","date":"2026-07-06 10:00:00","type":"1","did":"5551234567",
               "contact":"5551110001","message":"x","carrier_status":"",
               "col_media1":"","col_media2":"","col_media3":""}
            ]}
            """.trimIndent()
        )
        val message = api().fetchMessages("5551234567", 0, 1_800_000_000, 100).single()
        // 10:00 at a fixed -5 is 15:00 UTC: 1783350000. New York would give 1783346400.
        assertEquals(1_783_350_000L, message.sentAt)
    }

    @Test
    fun `no_sms and invalid_did come back empty rather than throwing`() = runBlocking {
        reply("""{"status":"no_sms"}""")
        assertTrue(api().fetchMessages("5551234567", 0, 1, 100).isEmpty())
        reply("""{"status":"invalid_did"}""")
        assertTrue(api().fetchMessages("5551234567", 0, 1, 100).isEmpty())
    }

    @Test
    fun `a real failure status throws so the poll can retry it`() = runBlocking {
        reply("""{"status":"invalid_credentials"}""")
        val failure = runCatching { api().fetchMessages("5551234567", 0, 1, 100) }.exceptionOrNull()
        assertTrue("expected ApiException, got $failure", failure is ApiException)
        assertEquals("invalid_credentials", (failure as ApiException).status)
    }

    @Test
    fun `the request carries the window, the number and a fixed offset`() = runBlocking {
        reply("""{"status":"success","sms":[]}""")
        api().fetchMessages("5551234567", 1_700_000_000, 1_700_086_400, 100)
        val body = server.takeRequest().body.readUtf8()
        assertTrue("method missing from $body", body.contains("getMMS"))
        assertTrue("fixed offset missing from $body", body.contains("-5"))
        assertTrue("did missing from $body", body.contains("5551234567"))
        assertTrue("limit missing from $body", body.contains("100"))
    }

    @Test
    fun `sending returns the id the API assigned`() = runBlocking {
        reply("""{"status":"success","sms":"9012"}""")
        assertEquals(9012L, api().sendSms("5551234567", "5551110001", "hello"))
    }

    @Test
    fun `a failed send throws and reports why`() = runBlocking {
        reply("""{"status":"invalid_dst"}""")
        val failure = runCatching { api().sendSms("5551234567", "5551110001", "hi") }
            .exceptionOrNull()
        assertTrue("expected ApiException, got $failure", failure is ApiException)
    }

    @Test
    fun `an unconfigured API says so without touching the network`() = runBlocking {
        val unconfigured = object : ApiSettings {
            override fun credentials(): Pair<String, String>? = null
            override fun endpoint() = server.url("/rest.php").toString()
        }
        val failure = runCatching { VoipMs(unconfigured).discoverDids() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(null, server.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun `mailboxes carry their counts and the transcription flag`() = runBlocking {
        reply(
            """{"status":"success","voicemails":[
                 {"mailbox":"1001","name":"Main","new":"3","urgent":"2",
                  "transcription":"yes","password":"1234"},
                 {"mailbox":"1002","name":"Side","new":"0","urgent":"0",
                  "transcription":"no"}]}"""
        )
        val boxes = api().mailboxes()
        assertEquals(2, boxes.size)
        assertEquals("Main", boxes[0].name)
        assertEquals(3, boxes[0].newCount)
        assertEquals(2, boxes[0].urgent)
        assertTrue(boxes[0].transcription)
        assertFalse(boxes[1].transcription)
    }

    @Test
    fun `no mailboxes is empty rather than an error`() = runBlocking {
        reply("""{"status":"no_mailbox"}""")
        assertTrue(api().mailboxes().isEmpty())
    }

    @Test
    fun `an empty folder is empty rather than an error`() = runBlocking {
        reply("""{"status":"no_voicemail"}""")
        assertTrue(api().voicemails("1001", "INBOX").isEmpty())
    }

    @Test
    fun `voicemail rows parse folder, caller, duration and flags`() = runBlocking {
        reply(
            """{"status":"success","messages":[
                 {"mailbox":"1001","folder":"INBOX","message_num":"7",
                  "date":"2026-11-06","callerid":"15551110002","duration":"00:00:06",
                  "urgent":"yes","listened":"no"}]}"""
        )
        val row = api().voicemails("1001").single()
        assertEquals(7, row.messageNum)
        assertEquals("INBOX", row.folder)
        assertEquals("2026-11-06", row.date)
        assertEquals("5551110002", row.callerId)
        assertEquals("00:00:06", row.duration)
        assertTrue(row.urgent)
        assertFalse(row.listened)
    }

    @Test
    fun `a recording is decoded from the base64 it arrives in`() = runBlocking {
        val audio = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00)
        val encoded = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
        reply(
            """{"status":"success","message":[
                 {"mailbox":"1001","folder":"INBOX","message_num":7,"data":"$encoded"}]}"""
        )
        val decoded = api().voicemailAudio("1001", "INBOX", 7)
        assertTrue("audio did not round-trip", audio.contentEquals(decoded))
    }

    @Test
    fun `an absurd audio payload is refused before it is decoded`() {
        val api = api()
        assertTrue(api.audioLengthAllowed(1024))
        assertFalse(api.audioLengthAllowed(Int.MAX_VALUE))
    }

    @Test
    fun `audio is accepted from an object instead of an array`() = runBlocking {
        val audio = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        val encoded = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
        reply("""{"status":"success","message":{"message_num":7,"data":"$encoded"}}""")
        val decoded = api().voicemailAudio("1001", "INBOX", 7)
        assertTrue("audio did not round-trip", audio.contentEquals(decoded))
    }

    @Test
    fun `an empty success names the request and the fields it did get`() = runBlocking {
        // Not a shape the API sends, but the failure mode that hid a real bug for two
        // releases: a success with nothing where the parser looks used to say
        // "no_message", which is a Fax status code and told nobody anything.
        reply("""{"status":"success","message":[]}""")
        val failure = runCatching { api().voicemailAudio("1001", "Old", 3) }
            .exceptionOrNull()
        assertNotNull("an empty response has to fail", failure)
        val text = failure?.message.orEmpty()
        assertTrue("request missing from '$text'", text.contains("1001/Old/3"))
        assertTrue("field names missing from '$text'", text.contains("status"))
    }

    @Test
    fun `voicemail requests carry a date range in the API's own format`() = runBlocking {
        reply("""{"status":"success","messages":[]}""")
        api().voicemails("1001", folder = "INBOX", fromSec = 0, toSec = 0)
        val body = server.takeRequest().body.readUtf8()
        assertTrue("date_from missing from $body", body.contains("date_from"))
        assertTrue("date_to missing from $body", body.contains("date_to"))
        // Epoch 0 read at the fixed -5 offset is the previous day, not 1970-01-01, which
        // is the whole reason this goes through the same formatter as everything else.
        assertTrue("wrong date for epoch 0: $body", body.contains("1969-12-31"))
    }

    @Test
    fun `marking listened sends the documented parameters`() = runBlocking {
        reply("""{"status":"success"}""")
        api().markVoicemailListened("1001", "INBOX", 7, listened = true)
        val body = server.takeRequest().body.readUtf8()
        assertTrue("method missing from $body", body.contains("markListenedVoicemailMessage"))
        assertTrue("mailbox missing from $body", body.contains("1001"))
        assertTrue("folder missing from $body", body.contains("INBOX"))
        assertTrue("listen flag missing from $body", body.contains("yes"))
    }
}
