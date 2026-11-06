package com.willingtocode.mydidstation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Background poll. Foreground refreshes call Sync directly instead of waiting. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container
        if (container.settings.credentials() == null) return Result.success()
        return try {
            container.sync.refreshAll()
            container.settings.setLastPollAt(System.currentTimeMillis())
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "mydidstation-poll"

        /** WorkManager's floor for periodic work. */
        const val MIN_BACKGROUND_MINUTES = 15L

        fun schedule(context: Context, rearm: Boolean = false) {
            val minutes = Settings(context).backgroundMinutes().toLong()
                .coerceAtLeast(MIN_BACKGROUND_MINUTES)
            val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES).build()
            // UPDATE keeps the existing schedule, which is what an app start wants.
            // A deliberate interval change needs CANCEL_AND_REENQUEUE, or the new
            // number would not apply until the old period ran out.
            val policy = if (rearm) {
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            } else {
                ExistingPeriodicWorkPolicy.UPDATE
            }
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, policy, request)
        }

        /**
         * When the periodic worker is next due, for the countdown in the UI.
         *
         * WorkManager reports [Long.MAX_VALUE] until a periodic job has run at
         * least once, so that is treated as unknown. Until then the estimate is
         * the last poll plus one interval, which is what the schedule will settle
         * into anyway.
         */
        fun nextRunMillis(context: Context, lastPollAt: Long): Long? {
            val scheduled = try {
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(WORK_NAME)
                    .get()
                    .firstOrNull()
                    ?.nextScheduleTimeMillis
            } catch (_: Exception) {
                null
            }
            if (scheduled != null && scheduled > 0L && scheduled != Long.MAX_VALUE) return scheduled
            val minutes = Settings(context).backgroundMinutes().toLong()
                .coerceAtLeast(MIN_BACKGROUND_MINUTES)
            return lastPollAt.takeIf { it > 0L }?.plus(TimeUnit.MINUTES.toMillis(minutes))
        }
    }
}
