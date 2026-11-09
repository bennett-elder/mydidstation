package com.willingtocode.mydidstation

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What a notification says, and when it stays quiet.
 *
 * Two things ship here that nobody can check by reading a list: one notification per
 * conversation, replaced rather than stacked, and the two suppressions, the thread on screen
 * and the mailbox on screen. Both fail silently in the direction of buzzing about something
 * the user is already looking at, which is the complaint that produces a review saying the
 * app nags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotifyTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var notifier: AndroidNotifier

    private val did = "5551234567"
    private val them = "5559990009"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        // Both are process-wide state that the screens set. A test that leaks one into the
        // next would make the suppression assertions depend on the order they run in.
        VisibleThread.key = null
        VisibleMailbox.key = null

        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        notifier = AndroidNotifier(context)
        notifier.ensureChannel()
    }

    @After
    fun tearDown() {
        VisibleThread.key = null
        VisibleMailbox.key = null
    }

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    private fun title(notification: Notification): String =
        notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

    /**
     * The conversation a messaging notification belongs to.
     *
     * Not `EXTRA_TITLE`: MessagingStyle decorates that with the sender's name in front of the
     * body, so asserting on it would pin the framework's formatting rather than the app's
     * choice of which conversation this notification is about.
     */
    private fun conversationTitle(notification: Notification): String =
        notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()

    private fun text(notification: Notification): String =
        notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

    @Test
    fun `a message posts one notification carrying the newest text`() {
        notifier.newIncoming(did, them, "first", count = 1)

        val one = posted().single()
        assertEquals(them, conversationTitle(one))
        assertEquals("first", text(one))
        assertEquals(1, one.number)
        assertEquals("messages", one.channelId)
    }

    @Test
    fun `a second message replaces the first instead of stacking`() {
        notifier.newIncoming(did, them, "first", count = 1)
        notifier.newIncoming(did, them, "second", count = 2)

        assertEquals("one per conversation, not one per message", 1, posted().size)
        val one = posted().single()
        assertEquals("second", text(one))
        assertEquals(2, one.number)
    }

    @Test
    fun `two conversations keep two notifications`() {
        notifier.newIncoming(did, them, "a", count = 1)
        notifier.newIncoming(did, "5558887777", "b", count = 1)
        assertEquals(2, posted().size)
    }

    @Test
    fun `the thread on screen never notifies and another one still does`() {
        VisibleThread.key = VisibleThread.of(did, them)
        notifier.newIncoming(did, them, "looked at", count = 1)
        assertTrue("the thread being read buzzed", posted().isEmpty())

        notifier.newIncoming(did, "5558887777", "elsewhere", count = 1)
        assertEquals("the suppression took the rest with it", 1, posted().size)
    }

    @Test
    fun `notifications turned off silence both paths`() {
        Settings(context).setNotificationsEnabled(false)
        notifier.newIncoming(did, them, "a", count = 1)
        notifier.newVoicemail("1001", "Front desk", count = 1)
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `a mailbox notification carries the count and not the recording`() {
        // Naming a caller or reading out a transcript here would mean fetching recordings
        // during a background poll, which is the work the poll is built not to do.
        notifier.newVoicemail("1001", "Front desk", count = 1)

        val one = posted().single()
        assertEquals("Front desk", title(one))
        assertEquals("1 new recording", text(one))
        assertEquals("voicemail", one.channelId)

        notifier.newVoicemail("1001", "Front desk", count = 3)
        assertEquals("one per mailbox, not one per recording", 1, posted().size)
        assertEquals("3 new recordings", text(posted().single()))
        assertEquals(3, posted().single().number)
    }

    @Test
    fun `a mailbox with no name falls back to its number`() {
        notifier.newVoicemail("1001", "", count = 2)
        assertEquals("Mailbox 1001", title(posted().single()))
    }

    @Test
    fun `the mailbox on screen never notifies and another one still does`() {
        VisibleMailbox.key = "1001"
        notifier.newVoicemail("1001", "Front desk", count = 2)
        assertTrue("the mailbox being read buzzed", posted().isEmpty())

        notifier.newVoicemail("1002", "Back office", count = 1)
        assertEquals(1, posted().size)
    }

    @Test
    fun `reading a thread or a mailbox withdraws its notification`() {
        notifier.newIncoming(did, them, "a", count = 1)
        notifier.newVoicemail("1001", "Front desk", count = 1)
        assertEquals(2, posted().size)

        notifier.cancel(did, them)
        assertEquals("the thread's notification stayed", 1, posted().size)
        assertEquals("the wrong one was withdrawn", "Front desk", title(posted().single()))

        notifier.cancel("1001")
        assertTrue("the mailbox's notification stayed", posted().isEmpty())
    }

    @Test
    fun `the two channels exist and are separate`() {
        // Voicemail has its own channel so it can be silenced without silencing texts, and
        // Android fixes a channel's importance when it is first created. The ids are spelled
        // out rather than read from the constants, because Android keeps per-channel settings
        // against these strings: renaming one would reset whatever a user has chosen for it.
        val channels = manager.notificationChannels.associateBy { it.id }
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channels["messages"]?.importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channels["voicemail"]?.importance)
        assertEquals("an unexpected extra channel would land in the user's settings", 2, channels.size)
    }
}
