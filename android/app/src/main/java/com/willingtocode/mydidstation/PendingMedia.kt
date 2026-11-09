package com.willingtocode.mydidstation

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * The picture behind a message that is still on its way out.
 *
 * A sent MMS has no local path in the database, because received pictures are URLs and this row
 * has no URL yet. Rather than a column and a migration for a state that lasts seconds, the
 * composer registers the picked file against the pending row's id, the bubble draws it from
 * here, and the entry goes away as soon as the row is confirmed or removed. Process-wide, like
 * [VisibleThread], because the row outlives the composition that created it.
 *
 * What this cannot do, and does not pretend to: survive the process. After a restart a pending
 * picture shows as text until the poll brings the real message back, which is at most one poll
 * and is why a schema change is not worth it.
 */
internal object PendingMedia {

    private val byMessage = ConcurrentHashMap<Long, Uri>()

    fun put(messageId: Long, uri: Uri) {
        byMessage[messageId] = uri
    }

    fun of(messageId: Long): Uri? = byMessage[messageId]

    fun forget(messageId: Long) {
        byMessage.remove(messageId)
    }

    /**
     * Drops entries for rows that no longer exist.
     *
     * Called after every reload. A row that was removed after a failed send takes its picture
     * with it, and a confirmed row keeps its entry harmlessly because the bubble prefers the
     * server's copy once there is one.
     */
    fun retain(messageIds: Set<Long>) {
        byMessage.keys.retainAll(messageIds)
    }

    fun clear() = byMessage.clear()
}
