package com.willingtocode.mydidstation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person

/**
 * Told about messages a poll has just stored. An interface so the sync layer
 * stays free of Android types and remains testable.
 */
fun interface Notifier {
    fun newIncoming(did: String, contact: String, body: String, count: Int)
}

/**
 * The conversation currently on screen. A message that arrives in the thread you
 * are already reading should not buzz, and the thread screen is the only place
 * that knows which one that is.
 */
object VisibleThread {
    @Volatile
    var key: String? = null

    fun of(did: String, contact: String): String = "$did|$contact"
}

/**
 * One notification per conversation, replaced as more messages arrive for the
 * same one. Posting is skipped for the thread on screen, when notifications are
 * off in the app, and when Android has not granted the permission.
 */
class AndroidNotifier(private val context: Context) : Notifier {

    private val manager = NotificationManagerCompat.from(context)

    /** Idempotent, so it is safe to call on every app start. */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "New messages on your VoIP.ms numbers"
        }
        manager.createNotificationChannel(channel)
    }

    override fun newIncoming(did: String, contact: String, body: String, count: Int) {
        if (VisibleThread.key == VisibleThread.of(did, contact)) return
        if (!Settings(context).notificationsEnabled()) return
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()

        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_DID, did)
            putExtra(MainActivity.EXTRA_CONTACT, contact)
            // A tap should reuse the running task instead of stacking activities.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tap = PendingIntent.getActivity(
            context,
            id(did, contact),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val me = Person.Builder().setName("Me").build()
        val them = Person.Builder().setName(contact).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(contact)
            .addMessage(body, System.currentTimeMillis(), them)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setStyle(style)
            .setContentTitle(contact)
            .setContentText(body)
            .setNumber(count)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Stated rather than inherited from a zero-initialised field, so a future
            // edit cannot flip message bodies onto a lock screen by accident. PRIVATE
            // shows the body unless the user has turned on "hide sensitive content",
            // which is what messaging apps do by default.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()

        // Android 13 can revoke the permission between the check above and here.
        try {
            manager.notify(id(did, contact), notification)
        } catch (_: SecurityException) {
            // Nothing to show the user from a background poll; the next one retries.
        }
    }

    /** Called when a thread is read, because its notification has nothing left to say. */
    fun cancel(did: String, contact: String) {
        manager.cancel(id(did, contact))
    }

    /** Notification ids must be stable per conversation, or each one stacks up. */
    private fun id(did: String, contact: String): Int =
        VisibleThread.of(did, contact).hashCode()

    private companion object {
        const val CHANNEL_ID = "messages"
    }
}
