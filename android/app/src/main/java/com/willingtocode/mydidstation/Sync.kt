package com.willingtocode.mydidstation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What one tap of the load-more control would fetch. */
data class LookBack(val days: Int, val more: Boolean)

/**
 * Polling engine. One request per DID per window, a fixed overlap to catch late
 * arrivals, and dedupe delegated to the schema (DECISIONS.md D8).
 *
 * Nothing is fetched for a number until it is opened. Opening it fills the last
 * INITIAL_WINDOW_SECONDS, and [lookBack] reaches further only when asked.
 */
class Sync(private val store: Store, private val api: VoipMs) {

    suspend fun discoverDids(): Int = withContext(Dispatchers.IO) {
        val dids = api.discoverDids()
        dids.forEach { store.putDid(it) }
        dids.size
    }

    /** Returns the number of messages that were new to us. */
    suspend fun refreshDid(did: String): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val cursor = store.windowEnd(did)
        val added = if (cursor == 0L) firstSync(did, now) else poll(did, cursor, now)
        store.setWindowEnd(did, now)
        added
    }

    /**
     * First look at a number, triggered by opening it. One window, stored read
     * because it predates the app and should not arrive as a badge.
     */
    private suspend fun firstSync(did: String, now: Long): Int {
        val from = now - INITIAL_WINDOW_SECONDS
        val added = ingest(did, fetchWindow(did, from, now), unread = false)
        store.setHistoryStart(did, from)
        return added
    }

    /** Steady state: one window that starts just before the last one ended. */
    private suspend fun poll(did: String, cursor: Long, now: Long): Int {
        val from = maxOf(cursor - OVERLAP_SECONDS, now - MAX_RANGE_SECONDS)
        return ingest(did, fetchWindow(did, from, now), unread = true)
    }

    /** What the next tap of load-more would pull, so the label can say how much. */
    suspend fun lookBackPlan(did: String): LookBack = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val floor = now - LOOKBACK_FLOOR_SECONDS
        val covered = coveredFrom(did, now)
        if (covered <= floor) {
            LookBack(days = 0, more = false)
        } else {
            val from = maxOf(covered - MAX_RANGE_SECONDS, floor)
            LookBack(
                days = ((covered - from) / DAY_SECONDS).toInt(),
                more = from > floor,
            )
        }
    }

    /** Pulls exactly the window [lookBackPlan] described. Returns new rows. */
    suspend fun lookBack(did: String): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val floor = now - LOOKBACK_FLOOR_SECONDS
        val covered = coveredFrom(did, now)
        if (covered <= floor) return@withContext 0
        val from = maxOf(covered - MAX_RANGE_SECONDS, floor)
        val added = ingest(did, fetchWindow(did, from, covered - 1), unread = false)
        store.setHistoryStart(did, from)
        added
    }

    /**
     * Oldest instant this number is known to be filled back to. A DID migrated
     * from version 1 has no record, so it is treated as the initial window.
     */
    private fun coveredFrom(did: String, now: Long): Long {
        val recorded = store.historyStart(did)
        return if (recorded > 0) recorded else now - INITIAL_WINDOW_SECONDS
    }

    private fun ingest(did: String, messages: List<RemoteMsg>, unread: Boolean): Int {
        var added = 0
        for (message in messages) {
            val isNew = store.upsertRemote(
                did = did,
                contact = message.contact,
                remoteId = message.remoteId,
                incoming = message.incoming,
                body = message.body,
                media = message.media,
                sentAt = message.sentAt,
                unread = unread && message.incoming,
            )
            if (isNew) added++
        }
        return added
    }

    /**
     * One window, halved while the API reports more rows than PAGE_LIMIT will
     * return. Without this a busy window silently loses its overflow, because
     * the cursor still advances past it. from and to are whole days on the
     * wire, so a window one day wide cannot be narrowed any further.
     */
    private suspend fun fetchWindow(
        did: String,
        from: Long,
        to: Long,
        depth: Int = 0,
    ): List<RemoteMsg> {
        val batch = api.fetchMessages(did, from, to, PAGE_LIMIT)
        if (batch.size < PAGE_LIMIT || depth >= MAX_SPLIT_DEPTH) return batch
        if (from >= to || api.sameApiDay(from, to)) return batch
        val mid = from + (to - from) / 2
        return fetchWindow(did, from, mid, depth + 1) +
            fetchWindow(did, mid + 1, to, depth + 1)
    }

    suspend fun refreshAll(): Int = withContext(Dispatchers.IO) {
        var added = 0
        for (did in store.enabledDids()) {
            try {
                added += refreshDid(did)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One bad DID must not abort the rest; the window is unchanged,
                // so the next poll retries it.
            }
        }
        added
    }

    /**
     * Delivers a message the UI has already shown as pending. The API returns the
     * new id, which becomes that row's remote_id, so the next poll dedupes onto it
     * instead of inserting a second copy. A failure removes the pending row and
     * rethrows, so the thread never keeps a message that was never sent.
     */
    suspend fun deliver(did: String, contact: String, body: String, pendingId: Long) =
        withContext(Dispatchers.IO) {
            try {
                val remoteId = api.sendSms(did, contact, body)
                if (remoteId != null) {
                    store.confirmPending(pendingId, remoteId)
                } else {
                    // No id came back, so this row cannot be matched to the real
                    // message. Drop it and let the next poll bring that one in.
                    store.deleteMessage(pendingId)
                }
                refreshDid(did)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.deleteMessage(pendingId)
                throw e
            }
        }

    private companion object {
        const val DAY_SECONDS = 24L * 60 * 60
        const val OVERLAP_SECONDS = DAY_SECONDS
        const val INITIAL_WINDOW_SECONDS = 30L * DAY_SECONDS
        const val MAX_RANGE_SECONDS = INITIAL_WINDOW_SECONDS
        const val LOOKBACK_FLOOR_SECONDS = 2L * 365 * DAY_SECONDS
        const val PAGE_LIMIT = 100
        const val MAX_SPLIT_DEPTH = 3
    }
}
