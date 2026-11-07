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
 * The migration chain, against a database shaped like the one a real user already
 * has. Five forward-only steps have never been tested, and one of them losing a
 * column or a row would be silent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StoreMigrationTest {

    private lateinit var context: Context

    @Before
    fun fresh() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("mydidstation.db")
    }

    @Test
    fun `a version 1 database migrates without losing anything`() {
        V1Database(context).use { legacy ->
            val db = legacy.writableDatabase
            db.execSQL(
                "INSERT INTO did (did, enabled, window_end, last_remote_id) " +
                    "VALUES ('5551234567', 1, 1700000000, 9001)"
            )
            db.execSQL(
                "INSERT INTO conversation (id, did, contact) " +
                    "VALUES (1, '5551234567', '5551110001')"
            )
            db.execSQL(
                "INSERT INTO message (conversation_id, did, remote_id, contact, incoming, " +
                    "body, sent_at, unread) VALUES (1, '5551234567', 9001, '5551110001', 1, " +
                    "'hello', 1700000000, 1)"
            )
        }

        val store = Store(context)
        val row = store.didsWithCounts().single()
        assertEquals("5551234567", row.did)
        assertEquals(1, row.total)
        assertEquals(1, row.unread)
        assertTrue("a migrated number stays enrolled", row.opened)
        assertEquals(1_700_000_000L, store.windowEnd("5551234567"))

        // Columns added later must default rather than be null or absent.
        assertEquals(0L, store.historyStart("5551234567"))
        val message = store.messages(store.conversationId("5551234567", "5551110001")).single()
        assertEquals("hello", message.body)
        assertFalse(message.pending)
        assertEquals("", message.carrierStatus)

        // v6 adds voicemail. The tables have to exist at the end of the chain, and the
        // existing DID row has to carry the new column rather than fail on it.
        store.putMailbox("1001", "After migration", newCount = 0, urgent = 0,
                         transcription = false)
        store.setDidMailbox("5551234567", "1001")
        assertEquals(1, store.mailboxes().single().dids)

        // v7 adds the per-mailbox coverage marker. A box that has never been filled
        // reads zero, and the table has to accept a first write.
        assertEquals(0L, store.voicemailWindowStart("1001"))
        store.setVoicemailWindowStart("1001", 1_700_000_000L)
        assertEquals(1_700_000_000L, store.voicemailWindowStart("1001"))
    }

    @Test
    fun `a version 7 database keeps its recordings through the rebuild`() {
        // v8 changes the primary key, which SQLite cannot alter, so the table is rebuilt and
        // the rows copied. A copy that loses a row or a column would be silent otherwise.
        V7Database(context).use { legacy ->
            val db = legacy.writableDatabase
            db.execSQL(
                "INSERT INTO voicemail (mailbox, folder, message_num, date, callerid, " +
                    "duration, urgent, listened, transcript) VALUES " +
                    "('1001', 'Old', 3, '2026-11-06', '5551110004', '00:00:12', 0, 1, 'kept')"
            )
        }

        val store = Store(context)
        val row = store.voicemails("1001").single()
        assertEquals("Old", row.folder)
        assertEquals(3, row.messageNum)
        assertEquals("kept", row.transcript)
        assertTrue(row.listened)

        // And the new key accepts what the old one refused.
        store.putVoicemail("1001", 3, "INBOX", "2026-11-06", "5551110005", "00:00:04", false, false)
        assertEquals(2, store.voicemails("1001").size)
    }

    /** The v7 schema, before the folder joined the primary key. */
    private class V7Database(context: Context) :
        SQLiteOpenHelper(context, "mydidstation.db", null, 7) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE did (
                     did TEXT PRIMARY KEY,
                     enabled INTEGER NOT NULL DEFAULT 0,
                     hidden INTEGER NOT NULL DEFAULT 0,
                     window_end INTEGER NOT NULL DEFAULT 0,
                     history_start INTEGER NOT NULL DEFAULT 0,
                     last_remote_id INTEGER,
                     mailbox TEXT NOT NULL DEFAULT ''
                   )"""
            )
            db.execSQL(
                """CREATE TABLE mailbox (
                     mailbox TEXT PRIMARY KEY,
                     name TEXT NOT NULL DEFAULT '',
                     transcription INTEGER NOT NULL DEFAULT 0,
                     new_count INTEGER NOT NULL DEFAULT 0,
                     urgent_count INTEGER NOT NULL DEFAULT 0
                   )"""
            )
            db.execSQL(
                """CREATE TABLE voicemail (
                     mailbox TEXT NOT NULL,
                     message_num INTEGER NOT NULL,
                     folder TEXT NOT NULL DEFAULT 'INBOX',
                     date TEXT NOT NULL DEFAULT '',
                     callerid TEXT NOT NULL DEFAULT '',
                     duration TEXT NOT NULL DEFAULT '',
                     urgent INTEGER NOT NULL DEFAULT 0,
                     listened INTEGER NOT NULL DEFAULT 0,
                     transcript TEXT NOT NULL DEFAULT '',
                     PRIMARY KEY (mailbox, message_num)
                   )"""
            )
            db.execSQL("CREATE INDEX voicemail_mailbox ON voicemail(mailbox, folder)")
            db.execSQL(
                """CREATE TABLE voicemail_window (
                     mailbox TEXT PRIMARY KEY,
                     start INTEGER NOT NULL DEFAULT 0
                   )"""
            )
            db.execSQL(
                """CREATE TABLE conversation (
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     did TEXT NOT NULL REFERENCES did(did) ON DELETE CASCADE,
                     contact TEXT NOT NULL,
                     UNIQUE(did, contact)
                   )"""
            )
            db.execSQL(
                """CREATE TABLE message (
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     conversation_id INTEGER NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
                     did TEXT NOT NULL,
                     remote_id INTEGER,
                     contact TEXT NOT NULL,
                     incoming INTEGER NOT NULL,
                     body TEXT NOT NULL DEFAULT '',
                     media1 TEXT NOT NULL DEFAULT '',
                     media2 TEXT NOT NULL DEFAULT '',
                     media3 TEXT NOT NULL DEFAULT '',
                     sent_at INTEGER NOT NULL,
                     unread INTEGER NOT NULL DEFAULT 0,
                     pending INTEGER NOT NULL DEFAULT 0,
                     carrier_status TEXT NOT NULL DEFAULT '',
                     UNIQUE(did, remote_id)
                   )"""
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    /** The v1 schema as the app first shipped it. */
    private class V1Database(context: Context) :
        SQLiteOpenHelper(context, "mydidstation.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE did (
                     did TEXT PRIMARY KEY,
                     enabled INTEGER NOT NULL DEFAULT 0,
                     window_end INTEGER NOT NULL DEFAULT 0,
                     last_remote_id INTEGER
                   )"""
            )
            db.execSQL(
                """CREATE TABLE conversation (
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     did TEXT NOT NULL REFERENCES did(did) ON DELETE CASCADE,
                     contact TEXT NOT NULL,
                     UNIQUE(did, contact)
                   )"""
            )
            db.execSQL(
                """CREATE TABLE message (
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     conversation_id INTEGER NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
                     did TEXT NOT NULL,
                     remote_id INTEGER,
                     contact TEXT NOT NULL,
                     incoming INTEGER NOT NULL,
                     body TEXT NOT NULL DEFAULT '',
                     media1 TEXT NOT NULL DEFAULT '',
                     media2 TEXT NOT NULL DEFAULT '',
                     media3 TEXT NOT NULL DEFAULT '',
                     sent_at INTEGER NOT NULL,
                     unread INTEGER NOT NULL DEFAULT 0,
                     UNIQUE(did, remote_id)
                   )"""
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
