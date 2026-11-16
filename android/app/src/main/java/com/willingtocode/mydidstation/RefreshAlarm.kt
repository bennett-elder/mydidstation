package com.willingtocode.mydidstation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * The wake-up a registration refresh needs to survive Doze.
 *
 * A coroutine `delay` is a wall-clock timer on a suspended CPU: it resumes when something else
 * wakes the device, which can be long after the binding has expired. `AlarmManager` is the
 * mechanism Android provides for work that has to happen while idle.
 */
internal interface LineAlarms {
    /** Asks for a wake at [afterSeconds] from now, for this line. */
    fun schedule(account: String, afterSeconds: Int)

    /** Withdraws any pending wake for this line. */
    fun cancel(account: String)
}

internal class AlarmLineAlarms(
    private val context: Context,
    private val log: (String) -> Unit,
) : LineAlarms {

    override fun schedule(account: String, afterSeconds: Int) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + afterSeconds * 1000L
        val pending = pending(context, account)
        // Exact alarms need a grant; without it the inexact form still runs in Doze but may be
        // deferred, so the log names which one was used.
        // Exact alarms need a grant, and the getter for it only exists from Android 12. Below
        // that the inexact form is the only one, which is also what an ungranted phone gets.
        // Without the version gate the call threw `NoSuchMethodError` on API 24 to 30, and it
        // threw outside the `runCatching` below, so the line never got an alarm at all.
        val exact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.canScheduleExactAlarms()
        val refused = runCatching {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }.exceptionOrNull()
        log(
            if (refused == null) {
                "$account: refresh alarm set ${if (exact) "exact" else "inexact (no exact-alarm grant)"} " +
                    "for ${afterSeconds} s from now"
            } else {
                "$account: the refresh alarm could not be set: " +
                    (refused.message ?: refused.javaClass.simpleName)
            },
        )
    }

    override fun cancel(account: String) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pending(context, account)
        manager.cancel(pending)
        pending.cancel()
    }

    private fun pending(context: Context, account: String): PendingIntent {
        val intent = Intent(context, RefreshReceiver::class.java)
            .putExtra(RefreshAlarm.EXTRA_ACCOUNT, account)
        // One alarm per line: the request code is the account's hash, so scheduling again
        // replaces rather than stacks.
        return PendingIntent.getBroadcast(
            context,
            account.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

internal object RefreshAlarm {
    const val EXTRA_ACCOUNT = "com.willingtocode.mydidstation.REFRESH_ACCOUNT"
}

/** Delivers a refresh wake to the line that asked for it. */
internal class RefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val account = intent.getStringExtra(RefreshAlarm.EXTRA_ACCOUNT) ?: return
        VoiceWake.refreshLine(account)
    }
}

/**
 * A screen wake is the other chance to notice an expired binding.
 *
 * An alarm that Doze defers can leave the line dead until the next maintenance window. The
 * person picking the phone up is a wake we get for free, so the lines check themselves then,
 * and only register if the grant is close to running out.
 */
internal class WakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> VoiceWake.refreshDueLines()
        }
    }
}
