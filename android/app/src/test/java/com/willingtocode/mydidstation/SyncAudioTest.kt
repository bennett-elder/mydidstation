package com.willingtocode.mydidstation

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The retry around one recording's download.
 *
 * A real account answers `data: "false"` instead of audio for a recording it cannot serve
 * at that moment, and the same request a moment later works. Both halves are tested here:
 * that the second attempt is what produces the file, and that giving up says how many
 * attempts were made rather than pretending the payload was the only problem.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncAudioTest {

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

    private fun sync(): Sync {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("mydidstation.db")
        val api = VoipMs(FakeSettings(server.url("/rest.php").toString()))
        return Sync(Store(context), api, Notifier { _, _, _, _ -> })
    }

    private fun cache(): VoicemailCache =
        VoicemailCache(RuntimeEnvironment.getApplication().cacheDir)

    /** The word the API sends when it cannot serve the audio right now. */
    private fun notReady(): MockResponse = MockResponse().setBody(
        """{"status":"success","message":{"mailbox":"1001","folder":"INBOX",""" +
            """"message_num":7,"data":"false"}}"""
    )

    private fun audio(): MockResponse {
        val bytes = byteArrayOf(0x49, 0x44, 0x33, 0x04)
        val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return MockResponse().setBody(
            """{"status":"success","message":{"mailbox":"1001","folder":"INBOX",""" +
                """"message_num":7,"data":"$encoded"}}"""
        )
    }

    @Test
    fun `a recording that answers with a word is fetched on the next attempt`() = runBlocking {
        server.enqueue(notReady())
        server.enqueue(audio())
        val file = sync().voicemailAudioFile(cache(), "1001", "INBOX", 7)
        assertEquals("the retry should be the request that succeeded", 2, server.requestCount)
        assertTrue("nothing was written", file.isFile && file.length() > 0)
        assertEquals(4L, file.length())
    }

    @Test
    fun `giving up names the attempt count`() = runBlocking {
        repeat(VOICEMAIL_AUDIO_ATTEMPTS) { server.enqueue(notReady()) }
        val failure = runCatching { sync().voicemailAudioFile(cache(), "1001", "INBOX", 7) }
            .exceptionOrNull()
        assertNotNull("a recording that never arrives has to fail", failure)
        assertTrue(
            "attempt count missing from '${failure?.message}'",
            failure?.message.orEmpty().contains("gave up after ${VOICEMAIL_AUDIO_ATTEMPTS} attempts"),
        )
        assertEquals(VOICEMAIL_AUDIO_ATTEMPTS, server.requestCount)
    }

    @Test
    fun `a cached recording is never fetched again`() = runBlocking {
        server.enqueue(audio())
        val sync = sync()
        sync.voicemailAudioFile(cache(), "1001", "INBOX", 7)
        sync.voicemailAudioFile(cache(), "1001", "INBOX", 7)
        assertEquals("the second tap should come off disk", 1, server.requestCount)
    }
}
