package com.willingtocode.mydidstation

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Screens in the Voicemail tab, mounted, with taps driven through them.
 *
 * This is the shape of the bug that shipped twice: the row was fine and the screen forgot to
 * pass it the callback, which no test that only calls functions can see. Everything here is
 * the real thing except the two parts a JVM cannot supply. The API is a MockWebServer, the
 * Keystore is a stand-in, and the notifier does nothing, so no device and no network are
 * involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VoicemailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var context: Context

    /** Which API methods the app asked for, in order. The only outside view of a tap. */
    private val asked = CopyOnWriteArrayList<String>()

    private val methods = listOf(
        "getDIDsInfo",
        "getVoicemails",
        "getVoicemailMessages",
        "getVoicemailMessageFile",
    )

    @Before
    fun start() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        asked.clear()

        server = MockWebServer()
        // A dispatcher rather than a queue: a screen asks for several things while it settles,
        // and matching on the method keeps this test out of the order they happen to arrive in.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val form = request.body.readUtf8()
                methods.filter { form.contains(it) }.forEach { asked += it }
                return when {
                    form.contains("getDIDsInfo") -> MockResponse().setBody(
                        """{"status":"success","dids":[""" +
                            """{"did":"5551234567","sms_enabled":"1","voicemail":"1001"}]}""",
                    )

                    form.contains("getVoicemails") -> MockResponse().setBody(
                        """{"status":"success","voicemails":[""" +
                            """{"mailbox":"1001","name":"Front desk","new":"2","urgent":"0",""" +
                            """"transcription":"yes"}]}""",
                    )

                    form.contains("getVoicemailMessages") -> MockResponse().setBody(
                        """{"status":"success","messages":[""" +
                            """{"mailbox":"1001","folder":"INBOX","message_num":"3",""" +
                            """"date":"2026-11-08 10:00:00","callerid":"5559990009",""" +
                            """"duration":"0:12","urgent":"no","listened":"no"}]}""",
                    )

                    // Three bytes of an ID3 header. Enough to be audio to the parser, and
                    // nowhere near enough for the shadow player to make a noise.
                    form.contains("getVoicemailMessageFile") ->
                        MockResponse().setBody("""{"status":"success","data":"SUQz"}""")

                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun container(): Container = Container(
        context,
        box = OpaqueBox(),
        notifierOverride = Notifier { _, _, _, _ -> },
    )

    private fun configured(): Container = container().apply {
        settings.save("user@example.com", "secret")
        settings.setEndpoint(server.url("/rest.php").toString())
    }

    private fun waitForRow(text: String) {
        // The rows arrive from a coroutine that goes out to the API, so the row has to be
        // waited for rather than assumed.
        compose.waitUntil(timeoutMillis = PATIENCE_MS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `tapping a mailbox row opens that mailbox`() {
        val container = configured()
        var opened: Pair<String, String>? = null
        compose.setContent {
            MyDidStationTheme {
                VoicemailScreen(
                    container = container,
                    onOpen = { mailbox, name -> opened = mailbox to name },
                )
            }
        }

        waitForRow("Front desk")
        compose.onNodeWithText("Front desk").performClick()

        assertEquals("1001" to "Front desk", opened)
    }

    @Test
    fun `tapping a recording asks the API for its audio`() {
        val container = configured()
        compose.setContent {
            MyDidStationTheme {
                MailboxScreen(container, mailbox = "1001", name = "Front desk", onBack = {})
            }
        }

        waitForRow("5559990009")
        compose.onNodeWithText("5559990009").performClick()

        // What a tap is supposed to start is a fetch of that recording. Playback is not
        // asserted: MediaPlayer is a shadow on the JVM, and the wiring is the thing that has
        // been missing before, not the decoder.
        compose.waitUntil(timeoutMillis = PATIENCE_MS) {
            asked.contains("getVoicemailMessageFile")
        }
    }
}

