package com.willingtocode.voicepathfinder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The calls this phone has read, kept so the history is a history.
 *
 * The previous shape was a JSON snapshot in preferences: every read replaced the whole list,
 * so a refresh that covered a shorter window erased calls it had already shown, and nothing
 * could tell one row from another because the provider's `uniqueid` was not even parsed.
 *
 * Two consequences followed. The same call could appear twice if the provider reported it
 * twice, and a call the provider later finished (a call that was in progress when it was
 * first read) could only be corrected by luck. Here a call is its `uniqueid`: a second read
 * of the same call replaces the row, which is how a duration appears after a call ends.
 *
 * A record the provider gives no `uniqueid` for still gets a stable key of its own fields, so
 * a missing id cannot collapse separate calls into one row.
 */
class CallStore(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    private val lock = Any()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE call (
                 id TEXT PRIMARY KEY,
                 date TEXT NOT NULL,
                 destination TEXT NOT NULL DEFAULT '',
                 disposition TEXT NOT NULL DEFAULT '',
                 description TEXT NOT NULL DEFAULT '',
                 seconds INTEGER NOT NULL DEFAULT 0,
                 logs TEXT NOT NULL DEFAULT '',
                 account TEXT NOT NULL DEFAULT '',
                 caller_id TEXT NOT NULL DEFAULT ''
               )""",
        )
        db.execSQL("CREATE INDEX call_date ON call(date)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Nothing has shipped under an older shape. A rebuild is the honest answer if that
        // changes, because this table holds history the provider can hand back on request.
        db.execSQL("DROP TABLE IF EXISTS call")
        onCreate(db)
    }

    /** Writes the records, replacing any row the provider has since corrected. */
    fun upsert(records: List<CallRecord>): Int = synchronized(lock) {
        if (records.isEmpty()) return 0
        val db = writableDatabase
        var written = 0
        db.beginTransaction()
        try {
            for (record in records) {
                db.insertWithOnConflict("call", null, values(record), SQLiteDatabase.CONFLICT_REPLACE)
                written++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        written
    }

    /** Everything the store holds, newest first. */
    fun all(): List<CallRecord> = synchronized(lock) {
        val out = mutableListOf<CallRecord>()
        readableDatabase.rawQuery(
            "SELECT id, date, destination, disposition, description, seconds, logs, account, " +
                "caller_id FROM call ORDER BY date DESC, id DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += CallRecord(
                    date = cursor.getString(1),
                    destination = cursor.getString(2),
                    disposition = cursor.getString(3),
                    description = cursor.getString(4),
                    seconds = cursor.getInt(5),
                    logs = cursor.getString(6),
                    account = cursor.getString(7),
                    callerId = cursor.getString(8),
                    id = cursor.getString(0),
                )
            }
        }
        out
    }

    /** Drops everything dated before [date], a `yyyy-MM-dd` day. Returns the rows removed. */
    fun pruneBefore(date: String): Int = synchronized(lock) {
        writableDatabase.delete("call", "date < ?", arrayOf(date))
    }

    /** The oldest call held, or null when the store is empty. */
    fun oldestDate(): String? = synchronized(lock) {
        readableDatabase.rawQuery("SELECT date FROM call ORDER BY date ASC LIMIT 1", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun count(): Int = synchronized(lock) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM call", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun values(record: CallRecord) = ContentValues().apply {
        put("id", keyOf(record))
        put("date", record.date)
        put("destination", record.destination)
        put("disposition", record.disposition)
        put("description", record.description)
        put("seconds", record.seconds)
        put("logs", record.logs)
        put("account", record.account)
        put("caller_id", record.callerId)
    }

    companion object {
        private const val NAME = "calls.db"
        private const val VERSION = 1

        /**
         * The row this record replaces, which is the provider's own id when it gives one.
         *
         * The fallback is the fields that make a leg different from another leg, so two calls
         * to the same number at the same second would still be two rows rather than one.
         */
        internal fun keyOf(record: CallRecord): String {
            val told = record.id.trim()
            if (told.isNotEmpty()) return told
            return "no-id:${record.date}|${record.destination}|${record.description}|${record.seconds}"
        }
    }
}
