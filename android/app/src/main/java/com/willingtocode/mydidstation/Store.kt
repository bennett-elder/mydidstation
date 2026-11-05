package com.willingtocode.mydidstation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One row per DID, with the counts the main list shows. */
data class DidRow(val did: String, val total: Int, val unread: Int, val lastAt: Long)

/** One row per conversation inside a DID. */
data class ConvRow(
    val id: Long,
    val contact: String,
    val preview: String,
    val unread: Int,
    val lastAt: Long,
)

data class MsgRow(
    val id: Long,
    val incoming: Boolean,
    val body: String,
    val media: List<String>,
    val sentAt: Long,
    val unread: Boolean,
)

/**
 * Raw SQLite, explicit schema, forward-only migrations, no destructive fallback
 * (DECISIONS.md D3, ARCHITECTURE-LESSONS.md 1.7). Dedupe lives in the schema via
 * UNIQUE(did, remote_id), not in application code (1.5).
 */
class Store(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    private val lock = Any()

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE did (
                 did TEXT PRIMARY KEY,
                 enabled INTEGER NOT NULL DEFAULT 1,
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
        db.execSQL("CREATE INDEX message_conv ON message(conversation_id, sent_at)")
        db.execSQL("CREATE INDEX message_did ON message(did, sent_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Forward-only. Never drop user data; add migrations as versions grow.
        // var v = oldVersion
        // if (v < 2) { ...; v = 2 }
    }

    fun putDid(did: String) = synchronized(lock) {
        val values = ContentValues().apply {
            put("did", did)
            put("enabled", 1)
        }
        writableDatabase.insertWithOnConflict("did", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun enabledDids(): List<String> = synchronized(lock) {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT did FROM did WHERE enabled = 1 ORDER BY did", null)
            .use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        out
    }

    fun didsWithCounts(): List<DidRow> = synchronized(lock) {
        val sql = """
            SELECT d.did,
                   (SELECT COUNT(*) FROM message m WHERE m.did = d.did) AS total,
                   (SELECT COUNT(*) FROM message m WHERE m.did = d.did AND m.unread = 1) AS unread,
                   (SELECT COALESCE(MAX(m.sent_at), 0) FROM message m WHERE m.did = d.did) AS last_at
              FROM did d
             WHERE d.enabled = 1
             ORDER BY last_at DESC, d.did ASC
        """
        val out = mutableListOf<DidRow>()
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(DidRow(c.getString(0), c.getInt(1), c.getInt(2), c.getLong(3)))
            }
        }
        out
    }

    fun windowEnd(did: String): Long = synchronized(lock) {
        readableDatabase.rawQuery("SELECT window_end FROM did WHERE did = ?", arrayOf(did)).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    fun setWindowEnd(did: String, end: Long) = synchronized(lock) {
        val values = ContentValues().apply { put("window_end", end) }
        writableDatabase.update("did", values, "did = ?", arrayOf(did))
    }

    fun conversationId(did: String, contact: String): Long = synchronized(lock) {
        readableDatabase.rawQuery(
            "SELECT id FROM conversation WHERE did = ? AND contact = ?",
            arrayOf(did, contact),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    /** Create the conversation row if it is missing; used when composing. */
    fun ensureConversation(did: String, contact: String): Long = synchronized(lock) {
        ensureConversation(writableDatabase, did, contact)
    }

    private fun ensureConversation(db: SQLiteDatabase, did: String, contact: String): Long {
        val existing = db.rawQuery(
            "SELECT id FROM conversation WHERE did = ? AND contact = ?",
            arrayOf(did, contact),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        if (existing != 0L) return existing
        val values = ContentValues().apply {
            put("did", did)
            put("contact", contact)
        }
        return db.insert("conversation", null, values)
    }

    /** Insert one API message. Returns true if it was new (dedupe via UNIQUE). */
    fun upsertRemote(
        did: String,
        contact: String,
        remoteId: Long?,
        incoming: Boolean,
        body: String,
        media: List<String>,
        sentAt: Long,
        unread: Boolean,
    ): Boolean = synchronized(lock) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val convId = ensureConversation(db, did, contact)
            val values = ContentValues().apply {
                put("conversation_id", convId)
                put("did", did)
                if (remoteId != null) put("remote_id", remoteId)
                put("contact", contact)
                put("incoming", if (incoming) 1 else 0)
                put("body", body)
                put("media1", media.getOrNull(0) ?: "")
                put("media2", media.getOrNull(1) ?: "")
                put("media3", media.getOrNull(2) ?: "")
                put("sent_at", sentAt)
                put("unread", if (unread) 1 else 0)
            }
            val row = db.insertWithOnConflict(
                "message", null, values, SQLiteDatabase.CONFLICT_IGNORE,
            )
            db.setTransactionSuccessful()
            row != -1L
        } finally {
            db.endTransaction()
        }
    }

    fun conversations(did: String): List<ConvRow> = synchronized(lock) {
        val sql = """
            SELECT c.id, c.contact,
                   COALESCE((SELECT body FROM message m2 WHERE m2.conversation_id = c.id
                              ORDER BY m2.sent_at DESC LIMIT 1), '') AS preview,
                   (SELECT COUNT(*) FROM message m3 WHERE m3.conversation_id = c.id
                     AND m3.unread = 1) AS unread,
                   COALESCE((SELECT MAX(m4.sent_at) FROM message m4
                              WHERE m4.conversation_id = c.id), 0) AS last_at
              FROM conversation c
             WHERE c.did = ?
             ORDER BY last_at DESC, c.contact ASC
        """
        val out = mutableListOf<ConvRow>()
        readableDatabase.rawQuery(sql, arrayOf(did)).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ConvRow(
                        id = c.getLong(0),
                        contact = c.getString(1),
                        preview = c.getString(2),
                        unread = c.getInt(3),
                        lastAt = c.getLong(4),
                    )
                )
            }
        }
        out
    }

    fun messages(conversationId: Long): List<MsgRow> = synchronized(lock) {
        val out = mutableListOf<MsgRow>()
        readableDatabase.rawQuery(
            """SELECT id, incoming, body, media1, media2, media3, sent_at, unread
                 FROM message WHERE conversation_id = ? ORDER BY sent_at ASC, id ASC""",
            arrayOf(conversationId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    MsgRow(
                        id = c.getLong(0),
                        incoming = c.getInt(1) == 1,
                        body = c.getString(2),
                        media = listOf(c.getString(3), c.getString(4), c.getString(5))
                            .filter { it.isNotEmpty() },
                        sentAt = c.getLong(6),
                        unread = c.getInt(7) == 1,
                    )
                )
            }
        }
        out
    }

    fun markConversationRead(conversationId: Long) = synchronized(lock) {
        writableDatabase.execSQL(
            "UPDATE message SET unread = 0 WHERE conversation_id = ?",
            arrayOf(conversationId),
        )
    }

    private companion object {
        const val NAME = "mydidstation.db"
        const val VERSION = 1
    }
}
