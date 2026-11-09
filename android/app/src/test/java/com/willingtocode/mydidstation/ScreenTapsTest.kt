package com.willingtocode.mydidstation

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
    private val methods = listOf("getDIDsInfo", "getMMS", "getSMS", "sendSMS", "getVoicemails")

    private val did = "5551234567"
    private val them = "5559990009"

    @Before
    fun start() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        asked.clear()

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val form = request.body.readUtf8()
                methods.filter { form.contains(it) }.forEach { asked += it }
                return when {
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
        compose.waitUntil(timeoutMillis = 10_000) {
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

        compose.waitUntil(timeoutMillis = 10_000) { asked.contains("sendSMS") }
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

        compose.waitUntil(timeoutMillis = 10_000) { container.settings.credentials() != null }
        assertEquals("user@example.com" to "secret", container.settings.credentials())
    }
}
