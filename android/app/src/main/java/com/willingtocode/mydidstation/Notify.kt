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

    /**
     * A mailbox gained recordings.
     *
     * Defaulted to nothing so a fake that only cares about texts stays a one-line lambda.
     * [count] is the mailbox's new count, not the number that arrived since the last poll,
     * because that is what the vendor reports and what the row will show.
     */
    fun newVoicemail(mailbox: String, name: String, count: Int) {}
}

/**
 * The mailbox currently on screen, for the same reason as [VisibleThread]: looking at the
 * recordings is reading them, and a notification about what you are looking at is noise.
 */
object VisibleMailbox {
    @Volatile
    var key: String? = null
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
        // Its own channel rather than the message one, so voicemail can be silenced
        // without silencing texts. Android decides what a channel may do when it is
        // created, so this has to exist up front.
        val voicemail = NotificationChannel(
            VOICEMAIL_CHANNEL_ID,
            "Voicemail",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "New recordings in your VoIP.ms mailboxes"
        }
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(voicemail)
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

    /**
     * One notification per mailbox, replaced as the count grows, so a mailbox with three
     * recordings says three rather than stacking three notifications.
     */
    override fun newVoicemail(mailbox: String, name: String, count: Int) {
        if (VisibleMailbox.key == mailbox) return
        if (!Settings(context).notificationsEnabled()) return
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()

        val label = name.ifBlank { "Mailbox $mailbox" }
        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_MAILBOX, mailbox)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tap = PendingIntent.getActivity(
            context,
            voicemailId(mailbox),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // The count, not the content. Naming a caller or reading out a transcript on a
        // notification would mean fetching recordings during a background poll, which is
        // exactly the work the poll is designed not to do.
        val text = if (count == 1) "1 new recording" else "$count new recordings"

        val notification = NotificationCompat.Builder(context, VOICEMAIL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(label)
            .setContentText(text)
            .setNumber(count)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // A mailbox name is not sensitive, and it is the one thing worth reading on a
            // lock screen. Stated rather than inherited, as with message bodies.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()

        try {
            manager.notify(voicemailId(mailbox), notification)
        } catch (_: SecurityException) {
            // Revoked between the check and the post; the next poll retries.
        }
    }

    /** Called when a thread is read, because its notification has nothing left to say. */
    fun cancel(did: String, contact: String) {
        manager.cancel(id(did, contact))
    }

    /** Same, for a mailbox that has just been opened. */
    fun cancel(mailbox: String) {
        manager.cancel(voicemailId(mailbox))
    }

    /** Notification ids must be stable per conversation, or each one stacks up. */
    private fun id(did: String, contact: String): Int =
        VisibleThread.of(did, contact).hashCode()

    /**
     * Kept clear of the conversation ids above by namespacing the string before hashing,
     * so a mailbox cannot collide with a thread and silently replace its notification.
     */
    private fun voicemailId(mailbox: String): Int = "mailbox|$mailbox".hashCode()

    private companion object {
        const val CHANNEL_ID = "messages"
        const val VOICEMAIL_CHANNEL_ID = "voicemail"
    }
}
