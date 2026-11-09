package com.willingtocode.mydidstation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** What one tap of the load-more control would fetch. */
data class LookBack(val days: Int, val more: Boolean)

/**
 * Attempts at one recording's audio, and the wait before each retry.
 *
 * Three, because a real account answers `data: "false"` instead of audio for a recording it
 * cannot serve at that moment and succeeds on the next request. Top level rather than in the
 * companion so a test can assert the count without the companion becoming public.
 */
internal const val VOICEMAIL_AUDIO_ATTEMPTS = 3
internal const val VOICEMAIL_AUDIO_RETRY_MS = 200L

/**
 * How many by-id fetches one ingest pass may make to fill in missing text.
 *
 * A list that reports rows with no body must not turn into a request per row on every
 * poll, so a handful per pass is the ceiling; the next pass picks up where this one
 * stopped.
 */
internal const val MAX_BODY_BACKFILL = 8


/**
 * Polling engine. One request per DID per window, a fixed overlap to catch late
 * arrivals, and dedupe delegated to the schema (DECISIONS.md D8).
 *
 * Nothing is fetched for a number until it is opened. Opening it fills the last
 * INITIAL_WINDOW_SECONDS, and [lookBack] reaches further only when asked.
 */
class Sync(
    private val store: Store,
    private val api: VoipMs,
    private val notifier: Notifier,
) {

    /** Records every number the account reports, returning how many are usable. */
    suspend fun discoverDids(): Int = withContext(Dispatchers.IO) {
        val found = api.discoverDids()
        found.forEach {
            store.putDid(it.did, hidden = !it.smsEnabled, mailbox = it.mailbox)
        }
        found.count { it.smsEnabled }
    }

    /**
     * Refreshes one mailbox's recordings over the same 30-day window the text path uses.
     *
     * The bound is not a preference. Asking for the mailbox's whole history is what
     * produces the Cloudflare 522 this project already hit with 90-day text windows, so
     * `MAX_RANGE_SECONDS` is reused rather than a second number being invented here.
     * Recordings older than the window need a load-more, which does not exist yet.
     */
    suspend fun refreshVoicemails(mailbox: String): Int = withContext(Dispatchers.IO) {
        val window = Windows.first(System.currentTimeMillis() / 1000)
        val found = api.voicemails(mailbox, fromSec = window.from, toSec = window.to)
        found.forEach {
            store.putVoicemail(
                mailbox = it.mailbox.ifBlank { mailbox },
                messageNum = it.messageNum,
                folder = it.folder,
                date = it.date,
                callerId = it.callerId,
                duration = it.duration,
                urgent = it.urgent,
                listened = it.listened,
            )
        }
        // Only the first fill sets the floor. A later refresh must not claim the mailbox
        // is covered further back than it is, and it must not move the floor forward
        // either, or a load-more would be undone by an ordinary refresh.
        if (store.voicemailWindowStart(mailbox) == 0L) store.setVoicemailWindowStart(mailbox, window.from)
        reconcile(mailbox, window.from, window.to, found)
        found.size
    }

    /**
     * Removes rows inside the window the server no longer reports.
     *
     * A recording that moved folder, or was deleted, leaves a row behind otherwise. The
     * window is what makes this safe: anything older than what was just fetched is never a
     * candidate, so a reconciled refresh cannot erase history it did not ask about.
     */
    private fun reconcile(mailbox: String, from: Long, to: Long, found: List<VoiceMailInfo>) {
        store.reconcileVoicemails(
            mailbox = mailbox,
            fromDate = api.apiDate(from),
            toDate = api.apiDate(to),
            seen = found.map { it.folder to it.messageNum }.toSet(),
        )
    }

    /** What the next tap of load-more would pull for this mailbox, so the label is honest. */
    suspend fun voicemailLookBackPlan(mailbox: String): LookBack = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        Windows.lookBackPlan(now, Windows.covered(store.voicemailWindowStart(mailbox), now))
    }

    /** Pulls exactly the window [voicemailLookBackPlan] described. Returns rows fetched. */
    suspend fun voicemailLookBack(mailbox: String): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val covered = Windows.covered(store.voicemailWindowStart(mailbox), now)
        val window = Windows.lookBackSpan(now, covered) ?: return@withContext 0
        val found = api.voicemails(mailbox, fromSec = window.from, toSec = window.to)
        found.forEach {
            store.putVoicemail(
                mailbox = it.mailbox.ifBlank { mailbox },
                messageNum = it.messageNum,
                folder = it.folder,
                date = it.date,
                callerId = it.callerId,
                duration = it.duration,
                urgent = it.urgent,
                listened = it.listened,
            )
        }
        store.setVoicemailWindowStart(mailbox, window.from)
        reconcile(mailbox, window.from, window.to, found)
        found.size
    }

    /**
     * The file for one recording, downloading only when the cache has lost it.
     *
     * A cache miss is not a failure. The OS evicts this directory whenever it wants the
     * space back, so playback re-fetches rather than telling the user their copy is gone.
     */
    suspend fun voicemailAudioFile(
        cache: VoicemailCache,
        mailbox: String,
        folder: String,
        messageNum: Int,
    ): File = withContext(Dispatchers.IO) {
        cache.existing(mailbox, folder, messageNum)
            ?: cache.write(mailbox, folder, messageNum, downloadAudio(mailbox, folder, messageNum))
    }

    /**
     * Downloads the audio, retrying the answer that is worth retrying.
     *
     * The API answers `data: "false"` for a recording it cannot serve at that moment, and
     * the same request a moment later works, which was measured on a real account rather
     * than assumed. Three attempts with a short wait turns that into an invisible delay.
     * A wrong identity is not retried: it fails as a plain error and no amount of waiting
     * changes it.
     */
    private suspend fun downloadAudio(mailbox: String, folder: String, messageNum: Int): ByteArray {
        var last: AudioUnavailable? = null
        repeat(VOICEMAIL_AUDIO_ATTEMPTS) { attempt ->
            try {
                return api.voicemailAudio(mailbox, folder, messageNum)
            } catch (e: AudioUnavailable) {
                last = e
                if (attempt < VOICEMAIL_AUDIO_ATTEMPTS - 1) {
                    delay(VOICEMAIL_AUDIO_RETRY_MS * (attempt + 1))
                }
            }
        }
        throw AudioUnavailable(
            "${last?.message ?: "no audio"} (gave up after $VOICEMAIL_AUDIO_ATTEMPTS attempts)",
        )
    }

    /**
     * Refreshes the mailbox list. One call: getVoicemails is the only method that
     * enumerates mailboxes, and a mailbox exists independently of any number.
     *
     * Messages are not fetched here. Opening a mailbox does that, the same way opening a
     * number is what fetches its texts (D8).
     */
    suspend fun refreshMailboxes(discover: Boolean = true): Int = withContext(Dispatchers.IO) {
        // The number-to-mailbox mapping comes from getDIDsInfo, which discovery already
        // reads, and nothing else refreshes it. Doing that here is what keeps a mailbox's
        // "N numbers" honest without making the user go through Settings after the account
        // is reconfigured, and it costs one request.
        if (discover) discoverDids()
        // Read before writing, because the comparison is the whole notification rule.
        val was = store.mailboxes().associate { it.mailbox to it.newCount }
        val found = api.mailboxes()
        found.forEach {
            store.putMailbox(
                mailbox = it.mailbox,
                name = it.name,
                newCount = it.newCount,
                urgent = it.urgent,
                transcription = it.transcription,
            )
        }
        risers(was, found).forEach { notifier.newVoicemail(it.mailbox, it.name, it.newCount) }
        found.size
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
        val window = Windows.first(now)
        val added = ingest(did, fetchWindow(did, window.from, window.to), unread = false)
        store.setHistoryStart(did, window.from)
        return added
    }

    /** Steady state: one window that starts just before the last one ended. */
    private suspend fun poll(did: String, cursor: Long, now: Long): Int {
        val window = Windows.steady(now, cursor)
        val fresh = mutableListOf<RemoteMsg>()
        val added = ingest(did, fetchWindow(did, window.from, window.to), unread = true, fresh = fresh)
        notifyNew(did, fresh)
        return added
    }

    /**
     * One notification per conversation, carrying the newest text and how many
     * arrived. The thread screen suppresses its own, so reading is not noisy.
     */
    private fun notifyNew(did: String, fresh: List<RemoteMsg>) {
        fresh.filter { it.incoming }.groupBy { it.contact }.forEach { (contact, messages) ->
            // The id breaks the tie when several messages share a second, which
            // the API does for anything arriving together.
            val newest = messages.maxWithOrNull(compareBy({ it.sentAt }, { it.remoteId }))
                ?: return@forEach
            notifier.newIncoming(did, contact, newest.body, messages.size)
        }
    }

    /** What the next tap of load-more would pull, so the label can say how much. */
    suspend fun lookBackPlan(did: String): LookBack = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        Windows.lookBackPlan(now, coveredFrom(did, now))
    }

    /** Pulls exactly the window [lookBackPlan] described. Returns new rows. */
    suspend fun lookBack(did: String): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val window = Windows.lookBackSpan(now, coveredFrom(did, now)) ?: return@withContext 0
        val added = ingest(did, fetchWindow(did, window.from, window.to), unread = false)
        store.setHistoryStart(did, window.from)
        added
    }

    /**
     * Oldest instant this number is known to be filled back to. A DID migrated
     * from version 1 has no record, so it is treated as the initial window.
     */
    private fun coveredFrom(did: String, now: Long): Long =
        Windows.covered(store.historyStart(did), now)

    /**
     * Stores a window's messages. [fresh] collects the ones that were new and
     * incoming, which is what a notification is for; older history and the first
     * look at a number pass null because they must not buzz.
     */
    private suspend fun ingest(
        did: String,
        messages: List<RemoteMsg>,
        unread: Boolean,
        fresh: MutableList<RemoteMsg>? = null,
    ): Int {
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
                carrierStatus = message.carrierStatus,
            )
            if (isNew) {
                added++
                if (message.incoming) fresh?.add(message)
            }
        }
        verifyBodies(did)
        return added
    }

    /**
     * Confirms incoming bodies against the by-id call, once each.
     *
     * The list can return a body with its non-ASCII characters removed, and leaves no mark:
     * a message that was one emoji arrives empty and one that was text plus an emoji arrives
     * as the text alone. Nothing in the stored row says which, so the only reliable check is
     * to ask for the message by id, and the only affordable rule is to ask once per message.
     * `body_checked` records that, so the sweep drains rather than running forever.
     */
    private suspend fun verifyBodies(did: String) {
        for (id in store.uncheckedIncoming(did, MAX_BODY_BACKFILL)) {
            val full = try {
                api.message(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Leave it unchecked: a network failure says nothing about the body.
                continue
            }
            // No answer means the id is not on the server any more, which is still an answer
            // about this row, so it is not asked again.
            if (full != null && full.body.isNotBlank()) {
                store.replaceBody(did, id, full.body)
            }
            store.markBodyChecked(did, id)
        }
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
        val (left, right) = Windows.halves(from, to)
        return fetchWindow(did, left.from, left.to, depth + 1) +
            fetchWindow(did, right.from, right.to, depth + 1)
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
        // Voicemail polls on the same cadence and in the same pass as texts. The count
        // comes from the mailbox list, which is one request for the whole account, so this
        // does not fetch recordings or even a message list in the background.
        try {
            refreshMailboxes()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Same reasoning as above: a failed voicemail poll must not cost the text poll.
        }
        added
    }

    /**
     * The mailboxes whose new count went up since the last poll.
     *
     * A count that fell is somebody reading their voicemail, which is not news. An
     * unchanged count is not news either. Only a rise is, and comparing against what was
     * stored is what keeps a recording from buzzing on every poll forever.
     */
    internal fun risers(before: Map<String, Int>, after: List<MailboxInfo>): List<MailboxInfo> =
        after.filter { (before[it.mailbox] ?: 0) < it.newCount }

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
        const val PAGE_LIMIT = 100
        const val MAX_SPLIT_DEPTH = 3
    }
}
