package com.willingtocode.mydidstation

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The store the call history lives in.
 *
 * The history used to be a JSON snapshot in preferences that every read replaced, and the
 * provider's `uniqueid` was not parsed, so a call had no identity. These pin the three things
 * that identity buys: the same call read twice is one row, a call the provider later finishes
 * is corrected rather than duplicated, and a record with no id of its own still stands apart
 * from its neighbours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CallStoreTest {

    private val store: CallStore
        get() = CallStore(RuntimeEnvironment.getApplication())

    private fun record(
        id: String,
        date: String = "2026-11-09 20:14:03",
        destination: String = "15551230000",
        seconds: Int = 21,
        disposition: String = "ANSWERED",
    ) = CallRecord(
        date = date,
        destination = destination,
        disposition = disposition,
        description = "Outbound - Customer",
        seconds = seconds,
        logs = "",
        account = "100000_line",
        callerId = "15550001111",
        id = id,
    )

    @Test
    fun the_same_call_read_twice_is_one_row() {
        val store = store
        store.upsert(listOf(record("9001")))
        store.upsert(listOf(record("9001")))
        assertEquals(1, store.count())
    }

    @Test
    fun a_call_that_finishes_is_corrected_not_duplicated() {
        val store = store
        // First read: the call is still up, so the provider reports nothing yet.
        store.upsert(listOf(record("9002", seconds = 0, disposition = "IN PROGRESS")))
        // Second read: the same uniqueid, now with a duration.
        store.upsert(listOf(record("9002", seconds = 47)))
        assertEquals(1, store.count())
        assertEquals(47, store.all().first().seconds)
        assertEquals("ANSWERED", store.all().first().disposition)
    }

    @Test
    fun the_history_reads_newest_first() {
        val store = store
        store.upsert(
            listOf(
                record("1", date = "2026-11-08 09:00:00"),
                record("2", date = "2026-11-09 20:14:03"),
                record("3", date = "2026-11-07 08:00:00"),
            ),
        )
        assertEquals(listOf("2", "1", "3"), store.all().map { it.id })
    }

    @Test
    fun a_record_with_no_id_is_still_its_own_row() {
        val store = store
        store.upsert(
            listOf(
                record("", destination = "15551230001"),
                record("", destination = "15551230002"),
            ),
        )
        assertEquals(2, store.count())
        assertNotEquals(CallStore.keyOf(record("")), CallStore.keyOf(record("", destination = "15551230002")))
    }

    @Test
    fun pruning_drops_what_is_older_than_the_kept_span() {
        val store = store
        store.upsert(
            listOf(
                record("old", date = "2026-08-01 10:00:00"),
                record("edge", date = "2026-08-17 10:00:00"),
                record("new", date = "2026-11-09 20:14:03"),
            ),
        )
        store.pruneBefore("2026-08-17")
        assertEquals(listOf("new", "edge"), store.all().map { it.id })
        assertTrue(store.count() == 2)
    }
}
