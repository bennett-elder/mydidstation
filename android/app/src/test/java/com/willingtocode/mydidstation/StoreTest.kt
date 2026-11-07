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
}
