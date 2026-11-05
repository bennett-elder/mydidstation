package com.willingtocode.mydidstation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Polling engine. One request per DID per window, a fixed overlap to catch late
 * arrivals, and dedupe delegated to the schema (DECISIONS.md D8).
 */
class Sync(private val store: Store, private val api: VoipMs) {

    suspend fun discoverDids(): Int = withContext(Dispatchers.IO) {
        val dids = api.discoverDids()
        dids.forEach { store.putDid(it) }
        dids.size
    }

    suspend fun ensureDid(did: String) = withContext(Dispatchers.IO) {
        store.putDid(VoipMs.normalizePhone(did))
    }

    /** Returns the number of messages that were new to us. */
    suspend fun refreshDid(did: String): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val cursor = store.windowEnd(did)
        val from = if (cursor == 0L) {
            now - DEFAULT_HISTORY_SECONDS
        } else {
            maxOf(cursor - OVERLAP_SECONDS, now - MAX_RANGE_SECONDS)
        }
        val messages = api.fetchMessages(did, from, now, PAGE_LIMIT)
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
                unread = message.incoming,
            )
            if (isNew) added++
        }
        store.setWindowEnd(did, now)
        added
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

    suspend fun send(did: String, contact: String, body: String) = withContext(Dispatchers.IO) {
        api.sendSms(did, contact, body)
        refreshDid(did)
    }

    private companion object {
        const val OVERLAP_SECONDS = 24L * 60 * 60
        const val DEFAULT_HISTORY_SECONDS = 30L * 24 * 60 * 60
        const val MAX_RANGE_SECONDS = 92L * 24 * 60 * 60
        const val PAGE_LIMIT = 100
    }
}
