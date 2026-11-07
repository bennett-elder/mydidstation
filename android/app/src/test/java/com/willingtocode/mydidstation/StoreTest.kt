package com.willingtocode.mydidstation

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The store against real SQLite, on the JVM, with no production change needed to
 * make it testable: Robolectric supplies the Context and SQLite is the real thing.
 *
 * `application = Application` deliberately bypasses this app's own `App` class,
 * whose `onCreate` builds the container and with it the Keystore-backed Settings,
 * which Robolectric does not implement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StoreTest {

    private lateinit var context: Context

    /** Must match Store.NAME. A rename breaks this, which is the point. */
    private val dbName = "mydidstation.db"

    @Before
    fun fresh() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(dbName)
    }

    private fun store() = Store(context)

    @Test
    fun `a discovered number is listed with its counts`() {
        val store = store()
        store.putDid("5551234567")
        val rows = store.didsWithCounts()
        assertEquals(1, rows.size)
        assertEquals("5551234567", rows[0].did)
        assertEquals(0, rows[0].total)
        assertFalse(rows[0].opened)
    }

    @Test
    fun `the same remote id is stored once`() {
        val store = store()
        store.putDid("5551234567")
        val first = store.upsertRemote(
            did = "5551234567", contact = "5551110001", remoteId = 9001, incoming = true,
            body = "hello", media = emptyList(), sentAt = 1_700_000_000L, unread = true,
            carrierStatus = "",
        )
        val second = store.upsertRemote(
            did = "5551234567", contact = "5551110001", remoteId = 9001, incoming = true,
            body = "hello", media = emptyList(), sentAt = 1_700_000_000L, unread = true,
            carrierStatus = "",
        )
        assertTrue(first)
        assertFalse(second)
        assertEquals(1, store.didsWithCounts().first().total)
    }

    @Test
    fun `a hidden number is neither listed nor polled`() {
        val store = store()
        store.putDid("5551234567", hidden = true)
        store.markOpened("5551234567")
        assertTrue(store.didsWithCounts().isEmpty())
        assertTrue(store.enabledDids().isEmpty())
    }

    @Test
    fun `re-discovery unhides a number without losing its enrolment`() {
        val store = store()
        store.putDid("5551234567", hidden = true)
        store.markOpened("5551234567")
        store.putDid("5551234567", hidden = false)
        assertEquals(listOf("5551234567"), store.enabledDids())
    }

    @Test
    fun `reading a conversation clears its unread marker`() {
        val store = store()
        store.putDid("5551234567")
        store.upsertRemote(
            did = "5551234567", contact = "5551110001", remoteId = 9001, incoming = true,
            body = "hello", media = emptyList(), sentAt = 1_700_000_000L, unread = true,
            carrierStatus = "",
        )
        store.markConversationRead(store.conversationId("5551234567", "5551110001"))
        assertEquals(0, store.didsWithCounts().first().unread)
    }

    @Test
    fun `a window is remembered per number`() {
        val store = store()
        store.putDid("5551234567")
        store.setWindowEnd("5551234567", 1_700_000_000L)
        store.setHistoryStart("5551234567", 1_600_000_000L)
        assertEquals(1_700_000_000L, store.windowEnd("5551234567"))
        assertEquals(1_600_000_000L, store.historyStart("5551234567"))
    }

    @Test
    fun `a shared mailbox counts every number pointing at it`() {
        val store = store()
        store.putDid("5551234567")
        store.putDid("5559876543")
        store.setDidMailbox("5551234567", "1001")
        store.setDidMailbox("5559876543", "1001")
        store.putMailbox("1001", "Shared", newCount = 2, urgent = 1, transcription = true)
        val row = store.mailboxes().single()
        assertEquals("Shared", row.name)
        assertEquals(2, row.newCount)
        assertEquals(1, row.urgent)
        assertTrue(row.transcription)
        assertEquals(2, row.dids)
    }

    @Test
    fun `the same number in two folders is two recordings`() {
        // The API numbers messages per folder, so INBOX 1 and Old 1 can both exist. The old
        // key was (mailbox, message_num) and one of these silently replaced the other.
        val store = store()
        store.putVoicemail("1001", 1, "INBOX", "2026-11-07", "5551110002", "00:00:06", false, false)
        store.putVoicemail("1001", 1, "Old", "2026-11-07", "5551110009", "00:00:12", false, true)
        val rows = store.voicemails("1001")
        assertEquals(2, rows.size)
        assertEquals(setOf("INBOX", "Old"), rows.map { it.folder }.toSet())
    }

    @Test
    fun `a refresh keeps a stored transcript`() {
        val store = store()
        store.putVoicemail("1001", 1, "INBOX", "2026-11-07", "5551110002", "00:00:06", false, false)
        store.setVoicemailTranscript("1001", "INBOX", 1, "call me back about the invoice")
        // The same recording arriving again, as a refresh would deliver it. REPLACE used to
        // delete and reinsert the row, taking the transcript with it.
        store.putVoicemail("1001", 1, "INBOX", "2026-11-07", "5551110002", "00:00:07", false, true)
        val row = store.voicemails("1001").single()
        assertEquals("call me back about the invoice", row.transcript)
    }

    @Test
    fun `reconciling drops a recording that moved and one that went`() {
        val store = store()
        store.putVoicemail("1001", 1, "INBOX", "2026-11-07", "5551110002", "00:00:06", false, false)
        store.putVoicemail("1001", 2, "INBOX", "2026-11-06", "5551110003", "00:00:03", false, false)
        // 3 describes a message outside the window, so no reconciliation may touch it.
        store.putVoicemail("1001", 3, "Old", "2026-01-02", "5551110004", "00:00:12", false, true)

        // Number 1 moved to Old, number 2 was deleted on the server.
        val removed = store.reconcileVoicemails(
            mailbox = "1001",
            fromDate = "2026-11-01",
            toDate = "2026-11-30",
            seen = setOf("Old" to 1),
        )
        assertEquals(2, removed)
        val rows = store.voicemails("1001")
        assertEquals(1, rows.size)
        assertEquals("2026-01-02", rows.single().date)
    }

    @Test
    fun `a mailbox coverage marker survives a rewrite`() {
        val store = store()
        // Never filled and empty are different answers, and zero is the first.
        assertEquals(0L, store.voicemailWindowStart("1001"))
        store.setVoicemailWindowStart("1001", 1_700_000_000L)
        store.setVoicemailWindowStart("1001", 1_600_000_000L)
        assertEquals(1_600_000_000L, store.voicemailWindowStart("1001"))
        // Per mailbox, not global.
        assertEquals(0L, store.voicemailWindowStart("1002"))
    }

    @Test
    fun `a mailbox with no number pointing at it still lists`() {
        val store = store()
        store.putMailbox("1002", "Unrouted", newCount = 0, urgent = 0, transcription = false)
        val row = store.mailboxes().single()
        assertEquals("1002", row.mailbox)
        assertEquals(0, row.dids)
        assertFalse(row.transcription)
    }
}
