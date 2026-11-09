package com.willingtocode.mydidstation

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The text screens, mounted, with taps driven through them.
 *
 * Same shape as `VoicemailScreenTest`: the real `Container`, the real `Store`, a MockWebServer
 * in place of the API, a test Keystore and a notifier that does nothing. What it buys is the
 * bug class that has shipped twice, where a row was wired correctly and the screen forgot to
 * pass it the callback, which nothing that only calls functions can see.
 *
 * Rows are seeded into the store rather than fetched, because what is under test is the tap
 * and not the parser. The mock answers anyway, so a screen that refreshes on mount finds a
 * well-formed empty account rather than a timeout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScreenTapsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var context: Context

    private val asked = CopyOnWriteArrayList<String>()

    /** When true, the next sendSMS is refused with [failNextSendWith] and the switch resets. */
    private var failNextSend = false
    private var failNextSendWith = 500
    private val methods = listOf("getDIDsInfo", "getMMS", "getSMS", "sendSMS", "getVoicemails")

    private val did = "5551234567"
    private val them = "5559990009"

    @Before
    fun start() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        asked.clear()
        // The picture map is process-wide, so one test must not inherit another's entry.
        PendingMedia.clear()

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val form = request.body.readUtf8()
                methods.filter { form.contains(it) }.forEach { asked += it }
                return when {
                    form.contains("sendSMS") && failNextSend -> {
                        failNextSend = false
                        MockResponse().setResponseCode(failNextSendWith)
                    }

                    form.contains("sendSMS") ->
                        MockResponse().setBody("""{"status":"success","sms":91001}""")

                    form.contains("getDIDsInfo") -> MockResponse().setBody(
                        """{"status":"success","dids":[{"did":"$did","sms_enabled":"1"}]}""",
                    )

                    form.contains("getVoicemails") ->
                        MockResponse().setBody("""{"status":"success","voicemails":[]}""")

                    // getMMS and getSMS: an account with nothing in it.
                    else -> MockResponse().setBody("""{"status":"success","sms":[]}""")
                }
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    /**
     * A container pointed at the mock.
     *
     * The permission prompt is marked as already asked. Android 13 would otherwise launch it
     * from the number list on first frame, which is a dialog this test is not about and
     * cannot answer.
     */
    private fun container(): Container =
        Container(context, box = OpaqueBox(), notifierOverride = Notifier { _, _, _, _ -> })
            .apply {
                settings.setEndpoint(server.url("/rest.php").toString())
                settings.setNotifyAsked()
            }

    private fun configured(): Container = container().apply {
        settings.save("user@example.com", "secret")
    }

    /** One conversation with one incoming message, which is all a row needs to exist. */
    private fun seedConversation(container: Container): Long {
        container.store.putDid(did)
        container.store.upsertRemote(
            did = did,
            contact = them,
            remoteId = 9001,
            incoming = true,
            body = "hello from them",
            media = emptyList(),
            sentAt = System.currentTimeMillis() / 1000,
            unread = true,
            carrierStatus = "",
        )
        return container.store.conversations(did).first().id
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = PATIENCE_MS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `tapping a number opens it`() {
        val container = configured()
        container.store.putDid(did)

        var opened: String? = null
        compose.setContent {
            MyDidStationTheme { DidListScreen(container, onOpen = { opened = it }) }
        }

        waitForText(did)
        compose.onNodeWithText(did).performClick()

        assertEquals(did, opened)
    }

    @Test
    fun `tapping a conversation opens it`() {
        val container = configured()
        val convId = seedConversation(container)

        var opened: Pair<Long, String>? = null
        compose.setContent {
            MyDidStationTheme {
                ConversationsScreen(
                    container = container,
                    did = did,
                    onBack = {},
                    onOpen = { id, contact -> opened = id to contact },
                    onNew = {},
                )
            }
        }

        waitForText(them)
        compose.onNodeWithText(them).performClick()

        assertEquals(convId to them, opened)
    }

    @Test
    fun `typing and tapping send asks the API to send the text`() {
        // The one action the app is for. A composer whose button is not wired looks exactly
        // like a working one until somebody tries to send.
        val container = configured()
        val convId = seedConversation(container)
        asked.clear()

        compose.setContent {
            MyDidStationTheme {
                MessagesScreen(
                    container = container,
                    did = did,
                    conversationId = convId,
                    contact = them,
                    onBack = {},
                )
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("hello there")
        compose.waitForIdle()
        compose.onNodeWithText("Send").performClick()

        compose.waitUntil(timeoutMillis = PATIENCE_MS) { asked.contains("sendSMS") }
    }

    @Test
    fun `the composer offers a picture and refuses an empty send`() {
        // The picker cannot be driven from a JVM test, so what is pinned here is the wiring
        // around it: the control exists, and Send stays disabled until there is something to
        // send. A composer that quietly enables Send with nothing in it is the bug this catches.
        val container = configured()
        val convId = seedConversation(container)

        compose.setContent {
            MyDidStationTheme {
                MessagesScreen(
                    container = container,
                    did = did,
                    conversationId = convId,
                    contact = them,
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Photo").assertExists()
        compose.onNodeWithText("Send").assertIsNotEnabled()

        compose.onNode(hasSetTextAction()).performTextInput("hello")
        compose.waitForIdle()
        compose.onNodeWithText("Send").assertIsEnabled()
    }

    @Test
    fun `a pending picture is drawn from the local file`() {
        // The row has no server media yet, so the only thing that can draw anything is the URI
        // the composer registered. Coil cannot decode under Robolectric, so this pins that the
        // bubble offers the node at all, which is the wiring rather than the decoding.
        val container = configured()
        seedConversation(container)
        val pendingId = container.store.insertPending(did, them, "", System.currentTimeMillis() / 1000)
        assertTrue("the pending row was not created", pendingId > 0)
        PendingMedia.put(pendingId, Uri.parse("content://probe/picture.jpg"))

        compose.setContent {
            MyDidStationTheme {
                MessagesScreen(
                    container = container,
                    did = did,
                    conversationId = container.store.ensureConversation(did, them),
                    contact = them,
                    onBack = {},
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("Attachment being sent")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `the server copy wins once it has arrived`() {
        // The bridge is only for the gap: a row that already carries the API's media must draw
        // that, not the local file, or a sent picture would keep being drawn from the phone
        // forever and a cached copy could disagree with the server's.
        val container = configured()
        container.store.putDid(did)
        container.store.upsertRemote(
            did = did,
            contact = them,
            remoteId = 9002,
            incoming = false,
            body = "",
            media = listOf("https://example.test/media.jpg"),
            sentAt = System.currentTimeMillis() / 1000,
            unread = false,
            carrierStatus = "",
        )
        val rowId = container.store.messages(container.store.ensureConversation(did, them)).first().id
        PendingMedia.put(rowId, Uri.parse("content://probe/local.jpg"))

        compose.setContent {
            MyDidStationTheme {
                MessagesScreen(
                    container = container,
                    did = did,
                    conversationId = container.store.ensureConversation(did, them),
                    contact = them,
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        assertTrue(
            "the local file was drawn over the server copy",
            compose.onAllNodesWithContentDescription("Attachment being sent")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `a pending row with no local file draws no picture`() {
        // The other half of the guard: same row, same empty media, no entry in the map.
        val container = configured()
        seedConversation(container)
        val pendingId = container.store.insertPending(did, them, "", System.currentTimeMillis() / 1000)
        assertTrue("the pending row was not created", pendingId > 0)

        compose.setContent {
            MyDidStationTheme {
                MessagesScreen(
                    container = container,
                    did = did,
                    conversationId = container.store.ensureConversation(did, them),
                    contact = them,
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        assertTrue(
            "something drew a picture with nothing to draw",
            compose.onAllNodesWithContentDescription("Attachment being sent")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `without a picture the counter is the SMS one`() {
        // Attaching a picture switches this to the 2000 character MMS rule, and that display
        // only appears with a picture, so the SMS analysis is what must still be here.
        val container = configured()
        val convId = seedConversation(container)

        compose.setContent {
            MyDidStationTheme {
                MessagesScreen(
                    container = container,
                    did = did,
                    conversationId = convId,
                    contact = them,
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("0 / 160").assertExists()
    }

    @Test
    fun `a refresh that works clears what the last one complained about`() {
        // A send that timed out on this side and arrived on the other leaves a complaint in the
        // pane, and the thread fills in behind it. Without this the pane keeps arguing with the
        // messages under it until the next send.
        val container = configured()
        val convId = seedConversation(container)
        // The dispatcher answers everything, so a failure has to come from it rather than from
        // enqueue: a custom dispatcher and a queue cannot coexist.
        failNextSend = true

        compose.setContent {
            MyDidStationTheme {
                MessagesScreen(
                    container = container,
                    did = did,
                    conversationId = convId,
                    contact = them,
                    onBack = {},
                )
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("will fail")
        compose.waitForIdle()
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("HTTP 500 from sendSMS").fetchSemanticsNodes().isNotEmpty()
        }

        // The next refresh succeeds, and the complaint has to go. The drag is given an
        // explicit length rather than the node's height: the list is short in this test window,
        // and a pull that stops a few pixels short of the trigger distance made this test
        // depend on the height of whatever was drawn below it.
        compose.onNode(hasScrollAction()).performTouchInput {
            swipeDown(startY = 0f, endY = 1_200f, durationMillis = 600)
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("HTTP 500 from sendSMS").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `saving credentials writes them where the API reads them`() {
        // The screen's whole job. The two fields are the only text inputs on it, so they are
        // addressed in order: that keeps this from depending on how the labels are worded.
        val container = container()
        compose.setContent {
            MyDidStationTheme { SettingsScreen(container, onDone = {}) }
        }

        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("user@example.com")
        fields[1].performTextInput("secret")
        compose.onNodeWithText("Save and find numbers").performClick()

        compose.waitUntil(timeoutMillis = PATIENCE_MS) { container.settings.credentials() != null }
        assertEquals("user@example.com" to "secret", container.settings.credentials())
    }

    @Test
    fun `a send that may have arrived is reported as maybe, with the check offered`() {
        // The measured case behind this: a 503 next to the send button while the picture had
        // in fact been delivered. Offering a plain retry there is how a message gets sent and
        // paid for twice, so the wording has to be the cautious one and Refresh has to be
        // reachable without waiting for the next poll.
        val container = configured()
        val convId = seedConversation(container)
        failNextSend = true
        failNextSendWith = 503

        compose.setContent {
            MyDidStationTheme {
                MessagesScreen(
                    container = container,
                    did = did,
                    conversationId = convId,
                    contact = them,
                    onBack = {},
                )
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("may have gone")
        compose.waitForIdle()
        compose.onNodeWithText("Send").performClick()

        compose.waitUntil(timeoutMillis = PATIENCE_MS) {
            compose.onAllNodesWithText("No answer from the server", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Send again").assertExists()
        compose.onNodeWithText("Refresh").assertExists()
        compose.onAllNodesWithText("Retry").assertCountEquals(0)
    }

    @Test
    fun `a send refused for a reason offers a plain retry`() {
        // 401 is decided before the message is looked at, so nothing exists to duplicate and
        // the card can be definite about it.
        val container = configured()
        val convId = seedConversation(container)
        failNextSend = true
        failNextSendWith = 401

        compose.setContent {
            MyDidStationTheme {
                MessagesScreen(
                    container = container,
                    did = did,
                    conversationId = convId,
                    contact = them,
                    onBack = {},
                )
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("will be refused")
        compose.waitForIdle()
        compose.onNodeWithText("Send").performClick()

        compose.waitUntil(timeoutMillis = PATIENCE_MS) {
            compose.onAllNodesWithText("Not sent", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Retry").assertExists()
        compose.onAllNodesWithText("Send again").assertCountEquals(0)
    }
}
