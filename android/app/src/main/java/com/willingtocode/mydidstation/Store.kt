package com.willingtocode.mydidstation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One row per DID, with the counts the main list shows. */
data class DidRow(
    val did: String,
    val total: Int,
    val unread: Int,
    val lastAt: Long,
    val opened: Boolean,
)

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
    val pending: Boolean,
    val carrierStatus: String,
)

/** One row per voicemail mailbox, with the counts the mailbox list shows. */
data class MailboxRow(
    val mailbox: String,
    val name: String,
    val newCount: Int,
    val urgent: Int,
    val transcription: Boolean,
    val dids: Int,
)

/** One recording. `date` is a date only, because that is all the API sends. */
data class VoiceMailRow(
    val mailbox: String,
    val messageNum: Int,
    val folder: String,
    val date: String,
    val callerId: String,
    val duration: String,
    val urgent: Boolean,
    val listened: Boolean,
    val transcript: String,
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
                 enabled INTEGER NOT NULL DEFAULT 0,
                 hidden INTEGER NOT NULL DEFAULT 0,
                 window_end INTEGER NOT NULL DEFAULT 0,
                 history_start INTEGER NOT NULL DEFAULT 0,
                 last_remote_id INTEGER,
                 mailbox TEXT NOT NULL DEFAULT ''
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
        db.execSQL("CREATE INDEX message_conv ON message(conversation_id, sent_at)")
        db.execSQL("CREATE INDEX message_did ON message(did, sent_at)")
        createVoicemailTables(db)
        createVoicemailWindowTable(db)
    }

    /**
     * The voicemail tables, in one place so the fresh-install schema and the migration
     * cannot drift apart.
     */
    /**
     * How far back each mailbox has been filled.
     *
     * A mailbox's coverage cannot be read off the messages it holds: a box with nothing
     * in the last 30 days has no rows at all, and inferring "we have seen nothing" from
     * that would hide load-more on exactly the sparse mailbox that needs it. This mirrors
     * `did.history_start`, which exists for the same reason.
     */
    private fun createVoicemailWindowTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE voicemail_window (
                 mailbox TEXT PRIMARY KEY,
                 start INTEGER NOT NULL DEFAULT 0
               )"""
        )
    }

    private fun createVoicemailTables(db: SQLiteDatabase) {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Forward-only. Never drop user data; add migrations as versions grow.
        // Blocks test oldVersion rather than a running counter, so a database
        // several versions behind still runs every step in order.
        if (oldVersion < 2) {
            // v2 fetches nothing for a number until you open it, so each DID has
            // to remember how far back it has been filled. Zero means "not known
            // yet", and the first look-back treats that as the initial window.
            db.execSQL("ALTER TABLE did ADD COLUMN history_start INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // v3 shows an outgoing message the moment you send it, so a row needs
            // to be able to say "not confirmed by the API yet".
            db.execSQL("ALTER TABLE message ADD COLUMN pending INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            // v4 keeps what the carrier says about the last leg. The poll already
            // fetches it; ignoring it meant a message marked undelivered looked
            // exactly like a delivered one.
            db.execSQL("ALTER TABLE message ADD COLUMN carrier_status TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 5) {
            // v5 records numbers the account cannot message on. They are kept
            // rather than deleted, because deleting a DID cascades to its
            // conversations and messages, and a number can be re-enabled later.
            db.execSQL("ALTER TABLE did ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 6) {
            // v6 holds voicemail, which the API keys by mailbox rather than by DID:
            // several numbers can point at one box, and a box can exist with no number
            // pointing at it. The column on `did` is reference only, kept so a mailbox
            // row can say how many numbers feed it.
            db.execSQL("ALTER TABLE did ADD COLUMN mailbox TEXT NOT NULL DEFAULT ''")
            createVoicemailTables(db)
        }
        if (oldVersion < 7) {
            // v7 remembers how far back each mailbox has been filled, so load-more can
            // step past the window even when the box returned nothing inside it.
            createVoicemailWindowTable(db)
        }
    }

    /**
     * Record a discovered number. A number the account cannot message on is kept
     * but marked hidden, so it disappears from the list without losing whatever
     * history it already had, and it comes back if SMS is enabled again.
     */
    fun putDid(did: String, hidden: Boolean = false, mailbox: String = "") = synchronized(lock) {
        val flag = if (hidden) 1 else 0
        val values = ContentValues().apply {
            put("did", did)
            put("enabled", 0)
            put("hidden", flag)
            put("mailbox", mailbox)
        }
        writableDatabase.insertWithOnConflict("did", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        // Existing rows must not lose their enrolled state, but the rest can change as the
        // account is reconfigured, so patch those columns alone. The mailbox is patched
        // even when empty: the API is the authority on where a number routes.
        val patch = ContentValues().apply {
            put("hidden", flag)
            put("mailbox", mailbox)
        }
        writableDatabase.update("did", patch, "did = ?", arrayOf(did))
    }

    /** Records a mailbox and the counts getVoicemails reports for it. */
    fun putMailbox(
        mailbox: String,
        name: String,
        newCount: Int,
        urgent: Int,
        transcription: Boolean,
    ) = synchronized(lock) {
        val values = ContentValues().apply {
            put("mailbox", mailbox)
            put("name", name)
            put("new_count", newCount)
            put("urgent_count", urgent)
            put("transcription", if (transcription) 1 else 0)
        }
        writableDatabase.insertWithOnConflict(
            "mailbox", null, values, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Which mailbox a number is routed to. Several may share one, and none may exist. */
    fun setDidMailbox(did: String, mailbox: String) = synchronized(lock) {
        val values = ContentValues().apply { put("mailbox", mailbox) }
        writableDatabase.update("did", values, "did = ?", arrayOf(did))
    }

    /** Records one recording, keyed by mailbox and message number. */
    fun putVoicemail(
        mailbox: String,
        messageNum: Int,
        folder: String,
        date: String,
        callerId: String,
        duration: String,
        urgent: Boolean,
        listened: Boolean,
    ) = synchronized(lock) {
        val values = ContentValues().apply {
            put("mailbox", mailbox)
            put("message_num", messageNum)
            put("folder", folder)
            put("date", date)
            put("callerid", callerId)
            put("duration", duration)
            put("urgent", if (urgent) 1 else 0)
            put("listened", if (listened) 1 else 0)
        }
        writableDatabase.insertWithOnConflict(
            "voicemail", null, values, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** One mailbox's recordings, newest first. The API gives a date and no time. */
    fun voicemails(mailbox: String): List<VoiceMailRow> = synchronized(lock) {
        val sql = """
            SELECT mailbox, message_num, folder, date, callerid, duration, urgent,
                   listened, transcript
              FROM voicemail
             WHERE mailbox = ?
             ORDER BY date DESC, message_num DESC
        """
        val out = mutableListOf<VoiceMailRow>()
        readableDatabase.rawQuery(sql, arrayOf(mailbox)).use { c ->
            while (c.moveToNext()) {
                out.add(
                    VoiceMailRow(
                        mailbox = c.getString(0),
                        messageNum = c.getInt(1),
                        folder = c.getString(2),
                        date = c.getString(3),
                        callerId = c.getString(4),
                        duration = c.getString(5),
                        urgent = c.getInt(6) == 1,
                        listened = c.getInt(7) == 1,
                        transcript = c.getString(8),
                    )
                )
            }
        }
        out
    }

    /** Mailboxes for the voicemail tab, busiest first, with how many numbers feed each. */
    fun mailboxes(): List<MailboxRow> = synchronized(lock) {
        val sql = """
            SELECT b.mailbox, b.name, b.new_count, b.urgent_count, b.transcription,
                   (SELECT COUNT(*) FROM did d WHERE d.mailbox = b.mailbox) AS dids
              FROM mailbox b
             ORDER BY b.new_count DESC, b.mailbox ASC
        """
        val out = mutableListOf<MailboxRow>()
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    MailboxRow(
                        mailbox = c.getString(0),
                        name = c.getString(1),
                        newCount = c.getInt(2),
                        urgent = c.getInt(3),
                        transcription = c.getInt(4) == 1,
                        dids = c.getInt(5),
                    )
                )
            }
        }
        out
    }

    /** Opening a number enrols it: from here it is polled and its counts shown. */
    fun markOpened(did: String) = synchronized(lock) {
        val values = ContentValues().apply { put("enabled", 1) }
        writableDatabase.update("did", values, "did = ?", arrayOf(did))
    }

    fun enabledDids(): List<String> = synchronized(lock) {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT did FROM did WHERE enabled = 1 AND hidden = 0 ORDER BY did", null,
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        out
    }

    fun didsWithCounts(): List<DidRow> = synchronized(lock) {
        val sql = """
            SELECT d.did,
                   (SELECT COUNT(*) FROM message m WHERE m.did = d.did) AS total,
                   (SELECT COUNT(*) FROM message m WHERE m.did = d.did AND m.unread = 1) AS unread,
                   (SELECT COALESCE(MAX(m.sent_at), 0) FROM message m WHERE m.did = d.did) AS last_at,
                   d.enabled AS opened
              FROM did d
             WHERE d.hidden = 0
             ORDER BY last_at DESC, d.did ASC
        """
        val out = mutableListOf<DidRow>()
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    DidRow(
                        did = c.getString(0),
                        total = c.getInt(1),
                        unread = c.getInt(2),
                        lastAt = c.getLong(3),
                        opened = c.getInt(4) == 1,
                    )
                )
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

    /** Oldest instant this DID has been filled back to. Zero means not known. */
    fun historyStart(did: String): Long = synchronized(lock) {
        readableDatabase.rawQuery("SELECT history_start FROM did WHERE did = ?", arrayOf(did)).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    fun setHistoryStart(did: String, start: Long) = synchronized(lock) {
        val values = ContentValues().apply { put("history_start", start) }
        writableDatabase.update("did", values, "did = ?", arrayOf(did))
    }

    /** How far back this mailbox has been filled. Zero means never filled. */
    fun voicemailWindowStart(mailbox: String): Long = synchronized(lock) {
        readableDatabase
            .rawQuery("SELECT start FROM voicemail_window WHERE mailbox = ?", arrayOf(mailbox))
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }

    fun setVoicemailWindowStart(mailbox: String, start: Long) = synchronized(lock) {
        val values = ContentValues().apply {
            put("mailbox", mailbox)
            put("start", start)
        }
        writableDatabase.insertWithOnConflict(
            "voicemail_window",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
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

    /**
     * Insert one API message, or refresh its carrier status if we already have it.
     * Returns true if the row was new (dedupe via UNIQUE(did, remote_id)).
     */
    fun upsertRemote(
        did: String,
        contact: String,
        remoteId: Long?,
        incoming: Boolean,
        body: String,
        media: List<String>,
        sentAt: Long,
        unread: Boolean,
        carrierStatus: String,
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
                put("carrier_status", carrierStatus)
            }
            val row = db.insertWithOnConflict(
                "message", null, values, SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (row == -1L && remoteId != null) {
                // The row exists, but the status moves over time: queued becomes
                // undelivered or delivered. Refresh only that column, so a repeat
                // poll cannot undo anything else, such as a read flag.
                val patch = ContentValues().apply { put("carrier_status", carrierStatus) }
                db.update(
                    "message", patch, "did = ? AND remote_id = ?",
                    arrayOf(did, remoteId.toString()),
                )
            }
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
            """SELECT id, incoming, body, media1, media2, media3, sent_at, unread, pending, carrier_status
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
                        pending = c.getInt(8) == 1,
                        carrierStatus = c.getString(9),
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

    /**
     * An outgoing message, stored before the API has seen it, so the thread can
     * show it immediately. Returns the row id to confirm or delete later.
     */
    fun insertPending(did: String, contact: String, body: String, sentAt: Long): Long =
        synchronized(lock) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val convId = ensureConversation(db, did, contact)
                val values = ContentValues().apply {
                    put("conversation_id", convId)
                    put("did", did)
                    put("contact", contact)
                    put("incoming", 0)
                    put("body", body)
                    put("sent_at", sentAt)
                    put("unread", 0)
                    put("pending", 1)
                }
                val id = db.insert("message", null, values)
                db.setTransactionSuccessful()
                id
            } finally {
                db.endTransaction()
            }
        }

    /**
     * The API accepted the message and returned its id. Writing that id means the
     * next poll's upsert collides with this row instead of adding a second copy.
     */
    fun confirmPending(id: Long, remoteId: Long) = synchronized(lock) {
        val values = ContentValues().apply {
            put("remote_id", remoteId)
            put("pending", 0)
        }
        writableDatabase.update("message", values, "id = ?", arrayOf(id.toString()))
    }

    fun deleteMessage(id: Long) = synchronized(lock) {
        writableDatabase.delete("message", "id = ?", arrayOf(id.toString()))
    }

    /** A send that never finished should not sit there saying it is sending. */
    fun dropStalePending(olderThan: Long) = synchronized(lock) {
        writableDatabase.delete("message", "pending = 1 AND sent_at < ?", arrayOf(olderThan.toString()))
    }

    private companion object {
        const val NAME = "mydidstation.db"
        const val VERSION = 7
    }
}
