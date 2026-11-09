package com.willingtocode.mydidstation

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tap, without an emulator.
 *
 * Twice a screen has shipped with no click handler on its obvious control, and a test suite
 * that only calls functions cannot see that: the missing thing is the wiring between a
 * row and its callback. This file is the standing check that the wiring can be exercised on
 * the JVM, in the same CI job that already runs everything else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ComposeTapTest {

    @get:Rule
    val compose = createComposeRule()

    private fun mailbox() = MailboxRow(
        mailbox = "1001",
        name = "Front desk",
        newCount = 2,
        urgent = 0,
        transcription = true,
        dids = 1,
    )

    @Test
    fun `a tap on a mailbox row reaches its handler`() {
        var taps = 0
        compose.setContent {
            MyDidStationTheme { MailboxRowItem(row = mailbox(), onClick = { taps++ }) }
        }

        // Twice, because one tap could be satisfied by a one-shot path that swallows the
        // second. The row has to keep answering for as long as it is on screen.
        compose.onNodeWithText("Front desk").performClick()
        compose.onNodeWithText("Front desk").performClick()

        assertEquals(2, taps)
    }
}
