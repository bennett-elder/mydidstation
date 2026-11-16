package com.willingtocode.mydidstation

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Answering opens the app's call window.
 *
 * The bug this pins down: Answer was a service action, so it took the call and left the person
 * in Android's incoming-call screen, which then went away, with this app never appearing. The
 * notification's Answer action has to be an activity intent that both answers and shows the
 * call, and the platform's Answer has to bring the same window up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CallAnswerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `the notification's Answer opens the call window and asks it to answer`() {
        IncomingAlerts.ringing(context, "5551234567", "5550001111")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(manager).allNotifications.single()

        val answer = posted.actions?.firstOrNull { it.title?.toString() == "Answer" }
        assertNotNull("the notification has no Answer action", answer)
        val opened = shadowOf(answer!!.actionIntent).savedIntent

        assertEquals(
            "Answer must start the call screen, not a service that only takes the call",
            CallActivity::class.java.name,
            opened.component?.className,
        )
        assertTrue(
            "the window was not told to answer",
            opened.getBooleanExtra(CallActivity.EXTRA_ANSWER, false),
        )
        assertTrue(
            "a call window started from a notification needs its own task",
            opened.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun `opening the window without answering carries no answer request`() {
        val opened = CallActivity.launchIntent(context)
        assertEquals(CallActivity::class.java.name, opened.component?.className)
        assertFalse(opened.getBooleanExtra(CallActivity.EXTRA_ANSWER, false))
    }

    @Test
    fun `open actually starts the call window`() {
        CallActivity.open(context)
        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(CallActivity::class.java.name, started.component?.className)
    }
}
