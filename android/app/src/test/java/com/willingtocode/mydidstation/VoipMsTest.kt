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
import java.util.Calendar
import java.util.TimeZone

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
    fun `awkward base64 still decodes`() {
        // Bytes chosen so the standard encoding contains both + and /, which are the
        // characters that turn into - and _ in the URL-safe alphabet.
        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0x3E, 0x7F, 0x2B)
        val standard = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        assertTrue("test bytes should exercise + and /", standard.any { it == '+' || it == '/' })

        val api = api()
        val withNewlines = standard.chunked(4).joinToString("\n")
        assertTrue(
            "wrapped base64 did not decode",
            bytes.contentEquals(api.decodeAudio(withNewlines)),
        )
        assertTrue(
            "URL-safe base64 did not decode",
            bytes.contentEquals(api.decodeAudio(standard.replace('+', '-').replace('/', '_'))),
        )
        assertTrue(
            "unpadded base64 did not decode",
            bytes.contentEquals(api.decodeAudio(standard.trimEnd('='))),
        )
        assertTrue(
            "base64 with spaces did not decode",
            bytes.contentEquals(api.decodeAudio(standard.chunked(2).joinToString(" "))),
        )
    }

    @Test
    fun `base64 that cannot be base64 says how much arrived`() {
        // A length congruent to 1 mod 4 cannot be whole bytes whatever padding is added,
        // and that is the shape the platform's decoder rejects.
        val failure = runCatching { api().decodeAudio("AAAAA") }.exceptionOrNull()
        assertNotNull("an impossible length has to fail", failure)
        val text = failure?.message.orEmpty()
        assertTrue("length missing from '$text'", text.contains("5 characters"))
        assertTrue("payload missing from '$text'", text.contains("\"AAAAA\""))
        // Nothing was discarded here, so the noise about stray characters stays out.
        assertFalse("needless stray note in '$text'", text.contains("not in the alphabet"))

        assertNotNull(runCatching { api().decodeAudio("") }.exceptionOrNull())
    }

    @Test
    fun `a shortcode can be replied to`() {
        // The reply that stops marketing traffic. Seven digits used to be the floor, so the
        // compose field refused every shortcode and the app could not send STOP.
        assertTrue(VoipMs.isSendable("22395"))
        assertTrue(VoipMs.isSendable("32665"))
    }

    @Test
    fun `a normal number in any shape is sendable`() {
        assertTrue(VoipMs.isSendable("5551234567"))
        assertTrue(VoipMs.isSendable("(555) 123-4567"))
        assertTrue(VoipMs.isSendable("+1 555 123 4567"))
        assertTrue(VoipMs.isSendable("+44 20 7946 0958"))
    }

    @Test
    fun `an empty or nonsense destination is not sendable`() {
        assertFalse(VoipMs.isSendable(""))
        assertFalse(VoipMs.isSendable("   "))
        assertFalse(VoipMs.isSendable("123"))
        assertFalse(VoipMs.isSendable("1234567890123456"))
    }

    @Test
    fun `a short code loses the country code the API added`() {
        // What the API actually sends for a five digit sender. Replying to the unstripped
        // form is answered with malformed-invalid-to-number, because it is not a number.
        assertEquals("89866", VoipMs.normalizePhone("189866"))
        assertEquals("898661", VoipMs.normalizePhone("1898661"))
    }

    @Test
    fun `a number loses its country code as it always did`() {
        assertEquals("8885551234", VoipMs.normalizePhone("18885551234"))
        assertEquals("5551234567", VoipMs.normalizePhone("(555) 123-4567"))
    }

    @Test
    fun `a short code with no prefix is left alone`() {
        assertEquals("89866", VoipMs.normalizePhone("89866"))
    }

    @Test
    fun `a short code that starts with one keeps its digits`() {
        // Somewhere other than NANP, a five digit code may begin with 1. The allocated
        // ranges say US and Canadian codes never do, so a remainder that starts with 1 is
        // not one, and stripping it would invent a different destination.
        assertEquals("112345", VoipMs.normalizePhone("112345"))
    }

    @Test
    fun `a value that is neither a number nor a short code is untouched`() {
        assertEquals("", VoipMs.normalizePhone(""))
        assertEquals("123", VoipMs.normalizePhone("123"))
        assertEquals("15551234", VoipMs.normalizePhone("15551234"))
    }

    @Test
    fun `a five character payload shows what it says`() = runBlocking {
        // The count alone was not enough to tell a sentinel from a broken body: five
        // characters, none of them outside the alphabet, is what the phones reported for
        // recordings that would not play. The characters have to be on the screen.
        reply(
            """{"status":"success","message":{"mailbox":"1001","folder":"Old",""" +
                """"message_num":3,"data":"false"}}"""
        )
        val failure = runCatching { api().voicemailAudio("1001", "Old", 3) }.exceptionOrNull()
        assertNotNull("a five character payload has to fail", failure)
        val text = failure?.message.orEmpty()
        assertTrue("payload missing from '$text'", text.contains("\"false\""))
        assertTrue("length missing from '$text'", text.contains("5 characters"))
        assertFalse(
            "the echo repeats the request here, so it should be left out: '$text'",
            text.contains("echoed"),
        )
    }

    @Test
    fun `a payload for the wrong folder names both identities`() = runBlocking {
        // Asked for INBOX, answered about Old: a move between refreshes, which is the
        // kind of drift a per-device database can hold.
        reply(
            """{"status":"success","message":{"mailbox":"1001","folder":"Old",""" +
                """"message_num":3,"data":"false"}}"""
        )
        val failure = runCatching { api().voicemailAudio("1001", "INBOX", 3) }.exceptionOrNull()
        val text = failure?.message.orEmpty()
        assertTrue("echo missing from '$text'", text.contains("the API echoed"))
        assertTrue("echoed folder missing from '$text'", text.contains("folder=Old"))
    }

    @Test
    fun `a stray character does not poison a good payload`() {
        val api = api()
        val clean = byteArrayOf(0x49, 0x44, 0x33)
        val encoded = android.util.Base64.encodeToString(clean, android.util.Base64.NO_WRAP)
        val polluted = encoded.replace("I", "I\u0000\u4e2d")
        assertTrue(
            "junk characters should be dropped, not fatal",
            clean.contentEquals(api.decodeAudio(polluted)),
        )
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

    /**
     * An instant written on the API's own day boundary, which is a fixed GMT-5 rather than
     * a city. The zone is named here instead of taken from the default, because a test that
     * leaned on the machine's zone would pass in one place and fail in another.
     */
    private fun apiDay(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("GMT-5")).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis / 1000

    @Test
    fun `two instants on one API day describe one window`() {
        // The sync layer narrows a range by halving it, and this is how it learns further
        // narrowing is pointless: both ends are the same date on the wire.
        val api = api()
        val midnight = apiDay(2026, 11, 8, 0, 0)
        val lastSecond = apiDay(2026, 11, 8, 23, 59, 59)
        assertTrue(api.sameApiDay(midnight, lastSecond))
        assertTrue(api.sameApiDay(lastSecond, midnight))
        assertTrue(api.sameApiDay(midnight, midnight))
    }

    @Test
    fun `one second past the API midnight is another day`() {
        val api = api()
        val lastSecond = apiDay(2026, 11, 8, 23, 59, 59)
        val nextMidnight = apiDay(2026, 11, 9, 0, 0)
        assertEquals(1L, nextMidnight - lastSecond)
        assertFalse(api.sameApiDay(lastSecond, nextMidnight))
    }

    @Test
    fun `the API day follows the fixed offset and not a city`() {
        // 19:30 and 23:30 on 1 July in GMT-5 are 20:30 and 00:30 in New York, so a reader
        // using America/New_York would call these two different days and disagree with the
        // server about which window it was given. The timestamps already cost an hour to
        // this same mistake.
        val api = api()
        val evening = apiDay(2026, 7, 1, 19, 30)
        val lateEvening = apiDay(2026, 7, 1, 23, 30)
        assertTrue(api.sameApiDay(evening, lateEvening))
        assertFalse(api.sameApiDay(lateEvening, apiDay(2026, 7, 2, 0, 30)))
    }

    @Test
    fun `the sendable range starts at a short code and ends at fifteen digits`() {
        // Both ends are refusals people meet in the composer: a truncated paste, and a wall
        // of digits. The middle is what has to keep working.
        assertFalse("four digits is shorter than any short code", VoipMs.isSendable("2239"))
        assertTrue("five digits is a short code", VoipMs.isSendable("22395"))
        assertTrue("fifteen digits is the E.164 ceiling", VoipMs.isSendable("123456789012345"))
        assertFalse("sixteen digits is past it", VoipMs.isSendable("1234567890123456"))
    }
}
